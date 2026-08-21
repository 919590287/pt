<template>
  <div class="vehicle-calc-panel">
    <div class="panel-header">
      <div class="header-title">
        <span class="title-mark"></span>
        <div>
          <div class="title-main">配车测算</div>
        </div>
      </div>
      <div class="header-actions">
        <el-button size="small" type="primary" @click.stop="generateTimetable">生成测算</el-button>
        <el-button
          size="small"
          type="success"
          :disabled="!selectedRouteInfo"
          :loading="savingResult"
          @click.stop="saveCalculationResult"
        >
          保存测算结果
        </el-button>
      </div>
    </div>

    <div class="panel-body">
      <section class="summary-strip" :class="{ error: Boolean(errorMessage) }">
        <template v-if="errorMessage">
          <div class="error-title">参数校验未通过</div>
          <div class="error-text">{{ errorMessage }}</div>
        </template>
        <template v-else>
          <div class="summary-card">
            <span class="summary-label">上行班次</span>
            <strong>{{ result.upTimes.length }}</strong>
            <span>班</span>
          </div>
          <div class="summary-card">
            <span class="summary-label">下行班次</span>
            <strong>{{ result.downTimes.length }}</strong>
            <span>班</span>
          </div>
          <div class="summary-card primary">
            <span class="summary-label">排班模拟配车</span>
            <strong>{{ result.schedule.vehicles }}</strong>
            <span>辆</span>
          </div>
          <div class="summary-card">
            <span class="summary-label">大车 / 小车</span>
            <strong>{{ result.schedule.largeCount }} / {{ result.schedule.smallCount }}</strong>
            <span>辆</span>
          </div>
          <div class="summary-card">
            <span class="summary-label">高峰折返配车</span>
            <strong>{{ result.peakRoundTripVehicles }}</strong>
            <span>辆</span>
          </div>
        </template>
      </section>

      <div class="workbench-grid">
        <aside class="input-column">
          <section class="form-section">
            <div class="section-title">线路参数导入（真实线路）</div>
            <el-select
              v-model="selectedRouteKey"
              class="route-select"
              size="small"
              filterable
              remote
              clearable
              reserve-keyword
              placeholder="按线路名称搜索，如 101路"
              no-data-text="未匹配到线路"
              loading-text="真实线路数据加载中"
              :loading="routeSource.status === 'loading'"
              :remote-method="handleRouteSearch"
              @visible-change="handleRouteDropdown"
              @change="handleRouteChange"
            >
              <el-option
                v-for="option in routeSearchResults"
                :key="option.key"
                :label="option.name"
                :value="option.key"
              >
                <span class="option-name">{{ option.name }}</span>
                <span class="option-meta">{{ option.endpointsText }}</span>
              </el-option>
            </el-select>

            <p v-if="routeSource.error" class="route-error">{{ routeSource.error }}</p>

            <div v-if="selectedRouteInfo" class="route-picked">
              <div class="route-picked-head">
                <span class="route-picked-name">{{ selectedRouteInfo.name }}</span>
                <button
                  class="swap-button"
                  type="button"
                  :disabled="selectedRouteInfo.directionCount < 2"
                  @click="swapDirections"
                >
                  ⇄ 交换上下行
                </button>
              </div>
              <div class="route-dir-row">
                <i class="dir-tag">上行</i>
                <span>{{ selectedRouteInfo.upLabel || "真实数据中缺此走向" }}</span>
              </div>
              <div class="route-dir-row">
                <i class="dir-tag down">下行</i>
                <span>{{ selectedRouteInfo.downLabel || "真实数据中缺此走向" }}</span>
              </div>
            </div>

            <div v-if="routeMissing.length" class="route-missing">
              <div class="route-missing-title">以下参数在真实数据中为空，请手动填写</div>
              <div v-for="group in routeMissingGroups" :key="group.name" class="route-missing-group">
                <i class="dir-tag" :class="{ down: group.name === '下行' }">{{ group.name }}</i>
                <div class="route-missing-tags">
                  <span v-for="item in group.items" :key="item.key">{{ item.label }}</span>
                </div>
              </div>
            </div>

            <p v-for="note in routeNotes" :key="note" class="route-note">{{ note }}</p>

          </section>

          <section class="form-section">
            <div class="section-title">服务时间设置</div>
            <div class="form-block-grid">
              <div class="form-block" :class="{ 'needs-input': isMissing('upService') }">
                <h3>上行服务时间</h3>
                <label class="field-row">
                  <span>开始</span>
                  <input v-model="form.upServiceStart" type="time" />
                </label>
                <div class="field-row">
                  <span class="field-head">
                    结束
                    <label class="next-day"><input v-model="form.upServiceEndNextDay" type="checkbox" />次日</label>
                  </span>
                  <input v-model="form.upServiceEnd" type="time" />
                </div>
              </div>
              <div class="form-block" :class="{ 'needs-input': isMissing('downService') }">
                <h3>下行服务时间</h3>
                <label class="field-row">
                  <span>开始</span>
                  <input v-model="form.downServiceStart" type="time" />
                </label>
                <div class="field-row">
                  <span class="field-head">
                    结束
                    <label class="next-day"><input v-model="form.downServiceEndNextDay" type="checkbox" />次日</label>
                  </span>
                  <input v-model="form.downServiceEnd" type="time" />
                </div>
              </div>
            </div>
          </section>

          <section class="form-section">
            <div class="section-title">
              高峰时段与发车间隔
              <button class="link-button" type="button" @click="copyUpPeaksToDown">上行参数复制到下行</button>
            </div>
            <div class="form-block-grid">
              <div
                v-for="direction in DIRECTIONS"
                :key="direction.key"
                class="form-block"
                :class="{ 'needs-input': hasMissingPeak(direction.key) }"
              >
                <h3>{{ direction.label }}</h3>
                <div class="sub-title">早高峰</div>
                <label class="field-row">
                  <span>开始</span>
                  <input v-model="form[`${direction.key}AmStart`]" type="time" />
                </label>
                <label class="field-row">
                  <span>结束</span>
                  <input v-model="form[`${direction.key}AmEnd`]" type="time" />
                </label>
                <label class="field-row">
                  <span>发车间隔 (分)</span>
                  <input v-model.number="form[`${direction.key}AmInterval`]" min="1" step="1" type="number" />
                </label>
                <div class="sub-title">晚高峰</div>
                <label class="field-row">
                  <span>开始</span>
                  <input v-model="form[`${direction.key}PmStart`]" type="time" />
                </label>
                <label class="field-row">
                  <span>结束</span>
                  <input v-model="form[`${direction.key}PmEnd`]" type="time" />
                </label>
                <label class="field-row">
                  <span>发车间隔 (分)</span>
                  <input v-model.number="form[`${direction.key}PmInterval`]" min="1" step="1" type="number" />
                </label>
                <div class="sub-title">平峰</div>
                <label class="field-row">
                  <span>发车间隔 (分)</span>
                  <input v-model.number="form[`${direction.key}OffInterval`]" min="1" step="1" type="number" />
                </label>
              </div>
            </div>
            <p class="interval-note">上下行各按自身的高峰时段与间隔发班；未设置某个高峰时，对应运营时段按该方向的平峰间隔。</p>
          </section>

          <section class="form-section">
            <div class="section-title">车辆调度参数</div>
            <div class="form-block-grid">
              <div class="form-block" :class="{ 'needs-input': isMissing('upDuration') || isMissing('downDuration') }">
                <h3>单程时间</h3>
                <label class="field-row">
                  <span>上行 (分)</span>
                  <input v-model.number="form.upDuration" min="1" step="1" type="number" />
                </label>
                <label class="field-row">
                  <span>下行 (分)</span>
                  <input v-model.number="form.downDuration" min="1" step="1" type="number" />
                </label>
              </div>
              <div class="form-block">
                <h3>折返与准点</h3>
                <label class="field-row">
                  <span>折返 (分)</span>
                  <input v-model.number="form.turnTime" min="0" step="1" type="number" />
                </label>
                <label class="field-row">
                  <span>允许晚点 (分)</span>
                  <input v-model.number="form.errorMargin" min="0" step="1" type="number" />
                </label>
              </div>
              <div class="form-block">
                <h3>线路长度</h3>
                <label class="field-row">
                  <span>上行 (km)</span>
                  <input v-model.number="form.upLength" min="0.1" step="0.1" type="number" />
                </label>
                <label class="field-row">
                  <span>下行 (km)</span>
                  <input v-model.number="form.downLength" min="0.1" step="0.1" type="number" />
                </label>
              </div>
              <div class="form-block">
                <h3>续航参数</h3>
                <label class="field-row">
                  <span>大车续航 (km)</span>
                  <input v-model.number="form.largeRange" min="1" step="1" type="number" />
                </label>
                <label class="field-row">
                  <span>小车续航 (km)</span>
                  <input v-model.number="form.smallRange" min="1" step="1" type="number" />
                </label>
              </div>
            </div>
            <p class="hint-text">先按大车续航生成调度，再根据实际里程分配车型；总里程不超过小车续航则判定为小车。</p>
          </section>
        </aside>

        <main class="result-column">
          <section class="result-section">
            <div class="section-title">排班计划与车辆配置</div>
            <div class="table-wrap compact">
              <table>
                <thead>
                  <tr>
                    <th>序号</th>
                    <th>上行发车</th>
                    <th>上行车辆</th>
                    <th>车型</th>
                    <th>下行发车</th>
                    <th>下行车辆</th>
                    <th>车型</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in result.timetableRows" :key="row.no">
                    <td>{{ row.no }}</td>
                    <td>{{ row.upTime }}</td>
                    <td>{{ row.upVehicle }}</td>
                    <td :class="{ small: row.upType === '小' }">{{ row.upType }}</td>
                    <td>{{ row.downTime }}</td>
                    <td>{{ row.downVehicle }}</td>
                    <td :class="{ small: row.downType === '小' }">{{ row.downType }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <section class="result-section">
            <div class="section-title">车辆发班明细</div>
            <div class="table-wrap">
              <table class="vehicle-detail-table">
                <thead>
                  <tr>
                    <th>车辆</th>
                    <th>车型</th>
                    <th>计划发车</th>
                    <th>实际发车</th>
                    <th>累计班次</th>
                    <th>累计里程(km)</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="vehicle in result.schedule.vehicleTasks" :key="vehicle.vehicleId">
                    <td>{{ vehicle.vehicleId }}</td>
                    <td :class="{ small: vehicle.type === '小' }">{{ vehicle.type }}</td>
                    <td class="time-list">
                      <span v-for="task in vehicle.tasks" :key="`${vehicle.vehicleId}-p-${task.direction}-${task.idx}`">
                        {{ task.direction === '上行' ? '↑' : '↓' }} {{ minutesToTime(task.planned) }}
                      </span>
                    </td>
                    <td class="time-list">
                      <span v-for="task in vehicle.tasks" :key="`${vehicle.vehicleId}-a-${task.direction}-${task.idx}`">
                        {{ task.direction === '上行' ? '↑' : '↓' }} {{ minutesToTime(task.actual) }}
                      </span>
                    </td>
                    <td>{{ vehicle.tasks.length }}</td>
                    <td>{{ vehicle.totalMileage.toFixed(1) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <section class="result-section diagram-section">
            <div class="section-title">车辆运行图（时间-空间轨迹）</div>
            <div ref="diagramWrapRef" class="diagram-wrap">
              <canvas
                ref="canvasRef"
                width="1000"
                height="450"
                @mouseleave="hideTooltip"
                @mousemove="handleCanvasMove"
              ></canvas>
              <div
                v-if="tooltip.visible"
                class="point-tooltip"
                :style="{ left: `${tooltip.x}px`, top: `${tooltip.y}px` }"
              >
                {{ tooltip.text }}
              </div>
            </div>
            <div class="legend">
              <span
                v-for="(vehicle, index) in result.schedule.vehicleTasks"
                :key="vehicle.vehicleId"
                class="legend-item"
              >
                <i :style="{ background: vehicleColor(index) }"></i>
                {{ vehicle.vehicleId }}（{{ vehicle.type }}）
              </span>
            </div>
            <div class="note-row">
              <span>实线：上行/下行任务</span>
              <span>虚线：折返等待</span>
              <span>{{ result.note }}</span>
            </div>
          </section>
        </main>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, shallowRef, watch } from "vue";
import { ElMessage } from "element-plus";
import { saveVehicleCalculationResult } from "@/api/realData.js";
import { isDarkTheme } from "@/utils/uiTheme";
import {
  getCachedRealData,
  invalidateCachedHistory,
  invalidateCachedRealData,
} from "@/utils/realDataCache.js";
import { DEFAULT_REAL_AREA } from "@/utils/realPassengerFlow.js";
import { buildRouteOptions, extractRouteFormValues, searchRouteOptions } from "./realRouteParams.js";
import { scheduleVehiclesOnly as calculateScheduleVehiclesOnly } from "./fleetCalculator.js";
import { generateDirectionTimeline } from "./timetable.js";

const canvasRef = ref(null);
const diagramWrapRef = ref(null);

const DIRECTIONS = Object.freeze([
  Object.freeze({ key: "up", label: "上行" }),
  Object.freeze({ key: "down", label: "下行" }),
]);

// 高峰时段与三档间隔都是方向级参数：同一条线的上下行在真实数据里经常不同
// （如南沙10路上行早高峰 07:00-08:00、下行 06:00-07:00），两侧各自独立填写与计算。
const DEFAULT_FORM = Object.freeze({
  upServiceStart: "05:30",
  upServiceEnd: "23:00",
  upServiceEndNextDay: false,
  downServiceStart: "05:30",
  downServiceEnd: "23:00",
  downServiceEndNextDay: false,
  upAmStart: "07:00",
  upAmEnd: "09:00",
  upPmStart: "17:00",
  upPmEnd: "19:00",
  upAmInterval: 30,
  upPmInterval: 30,
  upOffInterval: 60,
  downAmStart: "07:00",
  downAmEnd: "09:00",
  downPmStart: "17:00",
  downPmEnd: "19:00",
  downAmInterval: 30,
  downPmInterval: 30,
  downOffInterval: 60,
  upDuration: 60,
  downDuration: 60,
  turnTime: 25,
  errorMargin: 3,
  upLength: 20,
  downLength: 20,
  largeRange: 400,
  smallRange: 250,
});

const EMPTY_SCHEDULE = Object.freeze({
  vehicles: 0,
  largeCount: 0,
  smallCount: 0,
  upVehicle: [],
  upType: [],
  downVehicle: [],
  downType: [],
  vehicleTasks: [],
});

const form = reactive({ ...DEFAULT_FORM });
const errorMessage = ref("");
const vehiclePoints = ref([]);
const tooltip = reactive({ visible: false, x: 0, y: 0, text: "" });
const result = reactive({
  upTimes: [],
  downTimes: [],
  schedule: { ...EMPTY_SCHEDULE },
  peakRoundTripVehicles: 0,
  timetableRows: [],
  note: "",
});

const COLORS = [
  "#e23b3b", "#1569de", "#20a06b", "#7d5cc7", "#f08a24",
  "#b29b18", "#8f5a2b", "#d0529c", "#6b7280", "#12a6a6",
  "#f46f57", "#6375c9", "#d46aa7", "#80a93f", "#d6a500",
];

// 运行图中性 chrome（网格/站线/刻度/轴题）双主题取值，亮色值与原字面量一致；
// 车辆轨迹色 COLORS、折返虚线灰、发到点蓝底白圈为数据语义标记，不随主题翻转。
const DIAGRAM_INK = Object.freeze({
  light: Object.freeze({
    grid: "#dce7f2",
    station: "#9eb6cc",
    tickLine: "#8796a6",
    tickText: "#5f7083",
    stationLabel: "#1569de",
    axisTitle: "#22364c",
  }),
  dark: Object.freeze({
    grid: "rgba(148, 180, 220, 0.12)",
    station: "rgba(148, 180, 220, 0.28)",
    tickLine: "rgba(148, 180, 220, 0.28)",
    tickText: "#94a3b8",
    stationLabel: "#409cff",
    axisTitle: "#e7edf6",
  }),
});

// ── 真实线路参数导入 ──
// 线路来自真实数据 SHP（与数据管理页同一份 busLineStation 缓存，先开过数据管理页则秒开）。
// 整包偏大，改为首次展开下拉/输入时才拉取。
const routeSource = reactive({ status: "idle", error: "" });
const routeOptions = shallowRef([]);
const routeSearchResults = shallowRef([]);
const selectedRouteKey = ref("");
const selectedRouteInfo = shallowRef(null);
const routeMissing = shallowRef([]);
const routeNotes = shallowRef([]);
const directionsSwapped = ref(false);
const savingResult = ref(false);
const routeDataVersion = reactive({ revision: null, versionId: "" });
const missingKeys = computed(() => new Set(routeMissing.value.map((item) => item.key)));
// 缺失项按上下行分组展示，平铺成一串标签读不出属于哪个方向。
const routeMissingGroups = computed(() => {
  const groups = new Map();
  routeMissing.value.forEach((item) => {
    const name = item.group || "";
    if (!groups.has(name)) groups.set(name, { name, items: [] });
    groups.get(name).items.push(item);
  });
  return [...groups.values()];
});
let routeLoadPromise = null;

function isMissing(key) {
  return missingKeys.value.has(key);
}

function hasMissingPeak(direction) {
  return ["AmPeak", "PmPeak", "AmInterval", "PmInterval", "OffInterval"]
    .some((suffix) => isMissing(`${direction}${suffix}`));
}

function copyUpPeaksToDown() {
  ["AmStart", "AmEnd", "AmInterval", "PmStart", "PmEnd", "PmInterval", "OffInterval"].forEach((suffix) => {
    form[`down${suffix}`] = form[`up${suffix}`];
  });
  const copiedMissingKeys = new Set([
    "downAmPeak", "downPmPeak", "downAmInterval", "downPmInterval", "downOffInterval",
  ]);
  routeMissing.value = routeMissing.value.filter((item) => !copiedMissingKeys.has(item.key));
  generateTimetable();
}

function ensureRouteOptions() {
  if (routeSource.status === "ready") return Promise.resolve();
  if (routeLoadPromise) return routeLoadPromise;
  routeSource.status = "loading";
  routeSource.error = "";
  routeLoadPromise = getCachedRealData(DEFAULT_REAL_AREA)
    .then((data) => {
      routeOptions.value = buildRouteOptions(data?.lines);
      routeDataVersion.revision = Number.isFinite(Number(data?.history?.revision))
        ? Number(data.history.revision)
        : null;
      routeDataVersion.versionId = String(data?.versionId || data?.history?.activeVersionId || "");
      routeSource.status = routeOptions.value.length ? "ready" : "empty";
      if (!routeOptions.value.length) routeSource.error = "真实数据中没有可用线路";
    })
    .catch((error) => {
      routeSource.status = "error";
      routeSource.error = error?.message || "真实线路数据加载失败";
    })
    .finally(() => {
      routeLoadPromise = null;
    });
  return routeLoadPromise;
}

function refreshRouteResults(keyword) {
  routeSearchResults.value = searchRouteOptions(routeOptions.value, keyword, 30);
}

function handleRouteDropdown(visible) {
  if (!visible) return;
  ensureRouteOptions().then(() => refreshRouteResults(""));
}

function handleRouteSearch(keyword) {
  ensureRouteOptions().then(() => refreshRouteResults(keyword));
}

function handleRouteChange(key) {
  directionsSwapped.value = false;
  applySelectedRoute(key);
}

function swapDirections() {
  directionsSwapped.value = !directionsSwapped.value;
  applySelectedRoute(selectedRouteKey.value);
}

// 真实数据里为空的字段写回空值（而不是保留上一条线路的值或默认值）。
// 成对为空的高峰时段/间隔表示无该高峰；其他空值仍与 routeMissing 的待填提示对应。
function applySelectedRoute(key) {
  if (!key) {
    selectedRouteInfo.value = null;
    routeMissing.value = [];
    routeNotes.value = [];
    return;
  }
  const option = routeOptions.value.find((item) => item.key === key);
  if (!option) return;
  const { values, missing, notes, line } = extractRouteFormValues(option, { swapped: directionsSwapped.value });
  Object.assign(form, values);
  selectedRouteInfo.value = line;
  routeMissing.value = missing;
  routeNotes.value = notes;
  generateTimetable();
}

function toNumber(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function timeToMinutes(time) {
  if (!time) return 0;
  const [hour = "0", minute = "0"] = String(time).split(":");
  return Number.parseInt(hour, 10) * 60 + Number.parseInt(minute, 10);
}

function minutesToTime(minutes) {
  const totalMinutes = Math.round(minutes);
  const hour = Math.floor(totalMinutes / 60);
  const minute = totalMinutes % 60;
  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
}

function vehicleColor(index) {
  return COLORS[index % COLORS.length];
}

// 服务结束时刻允许跨零点（真实数据里夜班线末班写到 24:30 甚至 30:00），
// input[type=time] 只装得下钟面时刻，跨零点由「次日」勾选补 1440 分钟。
function serviceEndMinutes(direction) {
  const time = direction === "up" ? form.upServiceEnd : form.downServiceEnd;
  const nextDay = direction === "up" ? form.upServiceEndNextDay : form.downServiceEndNextDay;
  return timeToMinutes(time) + (nextDay ? 1440 : 0);
}

function validateDirectionPeaks(direction, label) {
  const amStart = form[`${direction}AmStart`];
  const amEnd = form[`${direction}AmEnd`];
  const pmStart = form[`${direction}PmStart`];
  const pmEnd = form[`${direction}PmEnd`];
  const amIntervalValue = form[`${direction}AmInterval`];
  const pmIntervalValue = form[`${direction}PmInterval`];
  const amInterval = toNumber(amIntervalValue);
  const pmInterval = toNumber(pmIntervalValue);
  const hasIntervalValue = (value) => value !== null && value !== undefined && value !== "";
  const hasAmPeak = Boolean(amStart || amEnd || hasIntervalValue(amIntervalValue));
  const hasPmPeak = Boolean(pmStart || pmEnd || hasIntervalValue(pmIntervalValue));
  if (toNumber(form[`${direction}OffInterval`]) < 1) return `${label}：平峰发车间隔必须大于等于 1`;
  if (hasAmPeak && (!amStart || !amEnd || amInterval < 1)) return `${label}：请完整填写早高峰时段与发车间隔`;
  if (hasPmPeak && (!pmStart || !pmEnd || pmInterval < 1)) return `${label}：请完整填写晚高峰时段与发车间隔`;
  if (hasAmPeak && timeToMinutes(amEnd) <= timeToMinutes(amStart)) return `${label}：早高峰结束时间必须晚于开始时间`;
  if (hasPmPeak && timeToMinutes(pmEnd) <= timeToMinutes(pmStart)) return `${label}：晚高峰结束时间必须晚于开始时间`;
  if (hasAmPeak && hasPmPeak && timeToMinutes(amEnd) > timeToMinutes(pmStart)) return `${label}：早高峰结束时间不能晚于晚高峰开始时间`;
  return "";
}

function validateParams() {
  if (!form.upServiceStart || !form.upServiceEnd || !form.downServiceStart || !form.downServiceEnd) {
    return "请完整填写上下行服务时间";
  }
  if (serviceEndMinutes("up") <= timeToMinutes(form.upServiceStart)) {
    return "上行：服务结束时间必须晚于开始时间（跨零点请勾选“次日”）";
  }
  if (serviceEndMinutes("down") <= timeToMinutes(form.downServiceStart)) {
    return "下行：服务结束时间必须晚于开始时间（跨零点请勾选“次日”）";
  }
  // 高峰参数逐方向校验：上下行各一套，报错要说清是哪个方向
  for (const direction of DIRECTIONS) {
    const error = validateDirectionPeaks(direction.key, direction.label);
    if (error) return error;
  }
  const positiveFields = [
    ["上行单程时间", form.upDuration, 1],
    ["下行单程时间", form.downDuration, 1],
    ["上行线路长度", form.upLength, 0.1],
    ["下行线路长度", form.downLength, 0.1],
    ["大车续航", form.largeRange, 1],
    ["小车续航", form.smallRange, 1],
  ];
  for (const [label, value, min] of positiveFields) {
    if (toNumber(value) < min) return `${label}必须大于 0`;
  }
  if (toNumber(form.turnTime) < 0 || toNumber(form.errorMargin) < 0) {
    return "折返时间和允许晚点不能为负数";
  }
  return "";
}

function directionParams(direction) {
  return {
    // 服务时段直接给分钟数：结束时刻可能跨零点（>1440），不能再走 timeToMinutes 的钟面口径
    serviceStart: timeToMinutes(direction === "up" ? form.upServiceStart : form.downServiceStart),
    serviceEnd: serviceEndMinutes(direction),
    amStart: form[`${direction}AmStart`],
    amEnd: form[`${direction}AmEnd`],
    amInterval: toNumber(form[`${direction}AmInterval`]),
    pmStart: form[`${direction}PmStart`],
    pmEnd: form[`${direction}PmEnd`],
    pmInterval: toNumber(form[`${direction}PmInterval`]),
    offInterval: toNumber(form[`${direction}OffInterval`]),
  };
}

function scheduleVehiclesOnly(upTimes, downTimes, upDur, downDur, turnTime, error, upLength, downLength, largeRange, smallRange) {
  return calculateScheduleVehiclesOnly(upTimes, downTimes, upDur, downDur, turnTime, error, upLength, downLength, largeRange, smallRange);
  /* legacy implementation retained below only in history; shared calculator is the source of truth. */
/*
  const tasks = [];
  upTimes.forEach((time, index) => {
    tasks.push({ id: `up_${index}`, direction: "上行", start: time, duration: upDur, from: "A", to: "B", idx: index, length: upLength });
  });
  downTimes.forEach((time, index) => {
    tasks.push({ id: `down_${index}`, direction: "下行", start: time, duration: downDur, from: "B", to: "A", idx: index, length: downLength });
  });
  tasks.sort((a, b) => a.start - b.start);

  const vehicles = [];
  const taskToVehicle = new Map();
  const actualDepart = new Map();

  for (const task of tasks) {
    const availableVehicles = [];
    for (let index = 0; index < vehicles.length; index += 1) {
      const vehicle = vehicles[index];
      if (
        vehicle.availableTime <= task.start + error
        && vehicle.currentStation === task.from
        && vehicle.totalMileage + task.length <= largeRange
      ) {
        availableVehicles.push(index);
      }
    }

    let bestIndex = null;
    let bestTaskCount = Infinity;
    let bestWaitTime = Infinity;
    for (const index of availableVehicles) {
      const vehicle = vehicles[index];
      const taskCount = vehicle.tasks.length;
      const waitTime = Math.max(0, task.start - vehicle.availableTime);
      const isOdd = taskCount % 2 === 1;
      if (bestIndex === null) {
        bestIndex = index;
        bestTaskCount = taskCount;
        bestWaitTime = waitTime;
        continue;
      }
      const bestIsOdd = bestTaskCount % 2 === 1;
      if (isOdd && !bestIsOdd) {
        bestIndex = index;
        bestTaskCount = taskCount;
        bestWaitTime = waitTime;
      } else if (isOdd === bestIsOdd) {
        if (taskCount < bestTaskCount || (taskCount === bestTaskCount && waitTime < bestWaitTime)) {
          bestIndex = index;
          bestTaskCount = taskCount;
          bestWaitTime = waitTime;
        }
      }
    }

    if (bestIndex !== null) {
      const vehicle = vehicles[bestIndex];
      const depart = Math.max(vehicle.availableTime, task.start);
      vehicle.tasks.push(task);
      vehicle.currentStation = task.to;
      vehicle.availableTime = depart + task.duration + turnTime;
      vehicle.totalMileage += task.length;
      actualDepart.set(task.id, depart);
      taskToVehicle.set(task.id, bestIndex);
    } else {
      const depart = task.start;
      vehicles.push({
        tasks: [task],
        currentStation: task.to,
        availableTime: depart + task.duration + turnTime,
        totalMileage: task.length,
      });
      actualDepart.set(task.id, depart);
      taskToVehicle.set(task.id, vehicles.length - 1);
    }
  }

  const vehicleTypes = vehicles.map((vehicle) => vehicle.totalMileage <= smallRange ? "small" : "large");
  const upVehicle = new Array(upTimes.length).fill("");
  const upType = new Array(upTimes.length).fill("");
  const downVehicle = new Array(downTimes.length).fill("");
  const downType = new Array(downTimes.length).fill("");

  tasks.forEach((task) => {
    const vehicleIndex = taskToVehicle.get(task.id);
    const vehicleLabel = `C${vehicleIndex + 1}`;
    const typeLabel = vehicleTypes[vehicleIndex] === "small" ? "小" : "大";
    if (task.direction === "上行") {
      upVehicle[task.idx] = vehicleLabel;
      upType[task.idx] = typeLabel;
    } else {
      downVehicle[task.idx] = vehicleLabel;
      downType[task.idx] = typeLabel;
    }
  });

  const vehicleTasks = vehicles.map((vehicle, vehicleIndex) => {
    let mileage = 0;
    const tasksWithMileage = vehicle.tasks
      .map((task) => ({
        direction: task.direction,
        planned: task.start,
        actual: actualDepart.get(task.id),
        duration: task.duration,
        idx: task.idx,
        from: task.from,
        to: task.to,
        length: task.length,
      }))
      .sort((a, b) => a.actual - b.actual)
      .map((task) => {
        mileage += task.length;
        return { ...task, mileage };
      });
    return {
      vehicleId: `C${vehicleIndex + 1}`,
      type: vehicleTypes[vehicleIndex] === "small" ? "小" : "大",
      totalMileage: vehicle.totalMileage,
      tasks: tasksWithMileage,
    };
  });

  return {
    vehicles: vehicles.length,
    largeCount: vehicles.filter((_, index) => vehicleTypes[index] === "large").length,
    smallCount: vehicles.filter((_, index) => vehicleTypes[index] === "small").length,
    upVehicle,
    upType,
    downVehicle,
    downType,
    vehicleTasks,
  };
*/
}

function buildTimetableRows(upTimes, downTimes, schedule) {
  let offset = 0;
  if (downTimes.length > 0) {
    const downFirst = downTimes[0];
    let found = -1;
    for (let index = 0; index < upTimes.length; index += 1) {
      if (upTimes[index] <= downFirst) found = index;
    }
    offset = found !== -1 ? found : 0;
  }
  const maxRows = Math.max(upTimes.length, downTimes.length + offset);
  return Array.from({ length: maxRows }, (_, index) => {
    const downIndex = index - offset;
    const hasDown = index >= offset && downIndex < downTimes.length;
    return {
      no: index + 1,
      upTime: index < upTimes.length ? minutesToTime(upTimes[index]) : "",
      upVehicle: index < schedule.upVehicle.length ? schedule.upVehicle[index] : "",
      upType: index < schedule.upType.length ? schedule.upType[index] : "",
      downTime: hasDown ? minutesToTime(downTimes[downIndex]) : "",
      downVehicle: hasDown ? schedule.downVehicle[downIndex] : "",
      downType: hasDown ? schedule.downType[downIndex] : "",
    };
  });
}

function generateTimetable() {
  const validationError = validateParams();
  if (validationError) {
    errorMessage.value = validationError;
    result.upTimes = [];
    result.downTimes = [];
    result.schedule = { ...EMPTY_SCHEDULE };
    result.peakRoundTripVehicles = 0;
    result.timetableRows = [];
    result.note = "";
    vehiclePoints.value = [];
    nextTick(drawVehicleDiagram);
    return;
  }

  errorMessage.value = "";
  const upTimes = generateDirectionTimeline(directionParams("up"));
  const downTimes = generateDirectionTimeline(directionParams("down"));
  const schedule = scheduleVehiclesOnly(
    upTimes,
    downTimes,
    toNumber(form.upDuration),
    toNumber(form.downDuration),
    toNumber(form.turnTime),
    toNumber(form.errorMargin),
    toNumber(form.upLength),
    toNumber(form.downLength),
    toNumber(form.largeRange),
    toNumber(form.smallRange),
  );

  result.upTimes = upTimes;
  result.downTimes = downTimes;
  result.schedule = schedule;
  // 折返配车按最密的那档高峰取值：上下行、早晚高峰的间隔各不相同时，配车规模由最小间隔决定
  const peakIntervals = [
    toNumber(form.upAmInterval),
    toNumber(form.upPmInterval),
    toNumber(form.downAmInterval),
    toNumber(form.downPmInterval),
  ].filter((interval) => interval >= 1);
  const tightestPeakInterval = peakIntervals.length ? Math.min(...peakIntervals) : null;
  result.peakRoundTripVehicles = tightestPeakInterval === null
    ? 0
    : Math.ceil((toNumber(form.upDuration) + toNumber(form.downDuration) + 2 * toNumber(form.turnTime)) / tightestPeakInterval);
  result.timetableRows = buildTimetableRows(upTimes, downTimes, schedule);
  result.note = `上行首班：${upTimes.length ? minutesToTime(upTimes[0]) : "无"}，下行首班：${downTimes.length ? minutesToTime(downTimes[0]) : "无"}，允许晚点 ≤ ${toNumber(form.errorMargin)} 分钟`;
  nextTick(drawVehicleDiagram);
}

async function saveCalculationResult() {
  if (savingResult.value) return;
  const option = routeOptions.value.find((item) => item.key === selectedRouteKey.value);
  if (!option) {
    ElMessage.warning("请先选择需要保存的真实线路");
    return;
  }

  generateTimetable();
  if (errorMessage.value || result.schedule.vehicles < 1) {
    ElMessage.warning(errorMessage.value || "当前没有可保存的配车测算结果");
    return;
  }
  const featureIds = [...new Set(option.features
    .map((feature) => String(feature?.id || feature?.properties?._featureId || "").trim())
    .filter(Boolean))];
  if (!featureIds.length || routeDataVersion.revision === null || !routeDataVersion.versionId) {
    ElMessage.warning("线路版本信息不完整，请重新选择线路后再保存");
    return;
  }

  savingResult.value = true;
  try {
    const response = await saveVehicleCalculationResult({
      areaName: DEFAULT_REAL_AREA,
      baseRevision: routeDataVersion.revision,
      baseVersionId: routeDataVersion.versionId,
      routeName: option.name,
      featureIds,
      vehicleCount: result.schedule.vehicles,
    }, { silentError: true });
    const saved = response?.data || {};
    routeDataVersion.revision = Number(saved.revision);
    routeDataVersion.versionId = String(saved.versionId || routeDataVersion.versionId);
    option.features.forEach((feature) => {
      if (feature?.properties) feature.properties.load_num = result.schedule.vehicles;
    });
    invalidateCachedRealData(DEFAULT_REAL_AREA);
    invalidateCachedHistory(DEFAULT_REAL_AREA);
    ElMessage.success(`已保存 ${option.name} 的配车数：${result.schedule.vehicles} 辆`);
  } catch (error) {
    ElMessage.error(error?.message || "保存测算结果失败，请稍后重试");
  } finally {
    savingResult.value = false;
  }
}

function stationY(station, marginTop, plotHeight) {
  return station === "A" ? marginTop + plotHeight * 0.2 : marginTop + plotHeight * 0.8;
}

function drawVehicleDiagram() {
  const canvas = canvasRef.value;
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  const width = canvas.width;
  const height = canvas.height;
  ctx.clearRect(0, 0, width, height);
  vehiclePoints.value = [];
  if (errorMessage.value || !result.schedule.vehicleTasks.length) return;

  const allEvents = [];
  result.schedule.vehicleTasks.forEach((vehicle) => {
    vehicle.tasks.forEach((task) => {
      allEvents.push({ time: task.actual });
      allEvents.push({ time: task.actual + task.duration });
    });
  });
  if (!allEvents.length) return;

  const minTime = Math.min(...allEvents.map((event) => event.time));
  const maxTime = Math.max(...allEvents.map((event) => event.time));
  const timeRange = maxTime - minTime || 1;
  const marginLeft = 70;
  const marginRight = 70;
  const marginTop = 40;
  const marginBottom = 44;
  const plotWidth = width - marginLeft - marginRight;
  const plotHeight = height - marginTop - marginBottom;
  const x = (time) => marginLeft + ((time - minTime) / timeRange) * plotWidth;
  const y = (station) => stationY(station, marginTop, plotHeight);

  const ink = isDarkTheme.value ? DIAGRAM_INK.dark : DIAGRAM_INK.light;
  ctx.lineWidth = 0.5;
  for (let index = 0; index <= 10; index += 1) {
    const time = minTime + (index / 10) * timeRange;
    const xPos = x(time);
    ctx.beginPath();
    ctx.moveTo(xPos, marginTop);
    ctx.lineTo(xPos, height - marginBottom);
    ctx.strokeStyle = ink.grid;
    ctx.stroke();
  }

  ctx.strokeStyle = ink.station;
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  ctx.moveTo(marginLeft, y("A"));
  ctx.lineTo(width - marginRight, y("A"));
  ctx.moveTo(marginLeft, y("B"));
  ctx.lineTo(width - marginRight, y("B"));
  ctx.stroke();

  ctx.fillStyle = ink.stationLabel;
  ctx.font = "700 18px system-ui, sans-serif";
  ctx.textAlign = "right";
  ctx.textBaseline = "top";
  ctx.fillText("首站", marginLeft - 8, y("A"));
  ctx.textBaseline = "bottom";
  ctx.fillText("末站", marginLeft - 8, y("B"));

  ctx.fillStyle = ink.tickText;
  ctx.font = "13px system-ui, sans-serif";
  ctx.textAlign = "center";
  const step = 120;
  const firstTick = Math.ceil(minTime / step) * step;
  for (let tick = firstTick; tick <= maxTime; tick += step) {
    const xPos = x(tick);
    ctx.beginPath();
    ctx.moveTo(xPos, y("A") - 10);
    ctx.lineTo(xPos, y("A") - 4);
    ctx.moveTo(xPos, y("B") + 10);
    ctx.lineTo(xPos, y("B") + 4);
    ctx.strokeStyle = ink.tickLine;
    ctx.lineWidth = 1;
    ctx.stroke();
    ctx.textBaseline = "bottom";
    ctx.fillText(minutesToTime(tick), xPos, y("A") - 13);
    ctx.textBaseline = "top";
    ctx.fillText(minutesToTime(tick), xPos, y("B") + 13);
  }

  result.schedule.vehicleTasks.forEach((vehicle, vehicleIndex) => {
    const color = vehicleColor(vehicleIndex);
    vehicle.tasks.forEach((task, taskIndex) => {
      const startX = x(task.actual);
      const endX = x(task.actual + task.duration);
      const startY = y(task.from);
      const endY = y(task.to);
      ctx.beginPath();
      ctx.moveTo(startX, startY);
      ctx.lineTo(endX, endY);
      ctx.strokeStyle = color;
      ctx.lineWidth = 3;
      ctx.stroke();

      vehiclePoints.value.push({ x: startX, y: startY, time: task.actual, station: task.from === "A" ? "首站" : "末站", type: "发车" });
      vehiclePoints.value.push({ x: endX, y: endY, time: task.actual + task.duration, station: task.to === "A" ? "首站" : "末站", type: "到达" });

      const nextTask = vehicle.tasks[taskIndex + 1];
      if (nextTask) {
        ctx.beginPath();
        ctx.moveTo(endX, endY);
        ctx.lineTo(x(nextTask.actual), endY);
        ctx.strokeStyle = "#a8b2bd";
        ctx.lineWidth = 2;
        ctx.setLineDash([6, 4]);
        ctx.stroke();
        ctx.setLineDash([]);
      }
    });
  });

  vehiclePoints.value.forEach((point) => {
    ctx.beginPath();
    ctx.arc(point.x, point.y, 5.5, 0, Math.PI * 2);
    ctx.fillStyle = "#1569de";
    ctx.fill();
    ctx.strokeStyle = "#fff";
    ctx.lineWidth = 2;
    ctx.stroke();
  });

  ctx.fillStyle = ink.axisTitle;
  ctx.font = "700 15px system-ui, sans-serif";
  ctx.textAlign = "center";
  ctx.fillText("时间", width / 2, height - 14);
}

function handleCanvasMove(event) {
  const canvas = canvasRef.value;
  if (!canvas) return;
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / rect.width;
  const scaleY = canvas.height / rect.height;
  const mouseX = (event.clientX - rect.left) * scaleX;
  const mouseY = (event.clientY - rect.top) * scaleY;
  let closest = null;
  let minDistance = Infinity;
  for (const point of vehiclePoints.value) {
    const distance = Math.hypot(mouseX - point.x, mouseY - point.y);
    if (distance < minDistance && distance < 15) {
      minDistance = distance;
      closest = point;
    }
  }
  if (!closest) {
    hideTooltip();
    return;
  }
  const wrapRect = diagramWrapRef.value?.getBoundingClientRect();
  tooltip.visible = true;
  tooltip.x = event.clientX - (wrapRect?.left || rect.left);
  tooltip.y = event.clientY - (wrapRect?.top || rect.top);
  tooltip.text = `${closest.station} ${closest.type} ${minutesToTime(closest.time)}`;
}

function hideTooltip() {
  tooltip.visible = false;
}

watch(
  () => result.schedule.vehicleTasks,
  () => nextTick(drawVehicleDiagram),
);

// 主题切换（html.dark 跟随底图选择）重绘运行图：仅中性 chrome 换色，数据色不变
watch(isDarkTheme, () => nextTick(drawVehicleDiagram));

onMounted(() => {
  generateTimetable();
});
</script>

<style lang="scss" scoped>
.vehicle-calc-panel {
  position: fixed;
  z-index: var(--z-panel);
  inset: var(--app-header-height) 0 0;
  width: 100vw;
  max-height: none;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: var(--app-ink);
  background: var(--app-panel-bg);
  border: 0;
  border-radius: 0;
  box-shadow: none;
}

.panel-header {
  flex: 0 0 auto;
  min-height: 54px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  padding: 10px 14px;
  user-select: none;
  background: rgba(21, 105, 222, 0.07);
  border-bottom: 1px solid rgba(21, 105, 222, 0.14);

}

.header-title {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.title-mark {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--app-blue);
  box-shadow: 0 4px 10px rgba(21, 105, 222, 0.22);
}

.title-main {
  font-size: 15px;
  font-weight: 760;
  color: var(--app-blue);
  line-height: 1.2;
}

.title-sub {
  margin-top: 2px;
  color: var(--app-muted);
  font-size: 12px;
  line-height: 1.2;
}

.header-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.panel-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: 14px;
  background: rgba(247, 251, 255, 0.78);
}

.summary-strip {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;

  &.error {
    display: block;
    padding: 12px 14px;
    border: 1px solid rgba(220, 76, 93, 0.25);
    border-radius: 10px;
    background: rgba(220, 76, 93, 0.08);
  }
}

.summary-card {
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid rgba(21, 105, 222, 0.12);
  border-radius: 10px;
  background: #fff;
  color: var(--app-ink-soft);
  font-size: 12px;
  font-variant-numeric: tabular-nums;

  strong {
    margin: 0 4px;
    color: var(--app-blue);
    font-family: var(--app-font-number);
    font-size: 22px;
    line-height: 1;
  }

  &.primary {
    border-color: rgba(21, 105, 222, 0.24);
    background: rgba(21, 105, 222, 0.06);
  }
}

.summary-label {
  display: block;
  margin-bottom: 5px;
  color: var(--app-muted);
  font-size: 12px;
  font-weight: 650;
}

.error-title {
  color: #b42335;
  font-size: 13px;
  font-weight: 750;
}

.error-text {
  margin-top: 4px;
  color: #8a2834;
  font-size: 13px;
}

.workbench-grid {
  display: grid;
  grid-template-columns: minmax(320px, 0.66fr) minmax(760px, 1.9fr);
  gap: 12px;
  align-items: start;
}

.input-column,
.result-column {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.form-section,
.result-section {
  min-width: 0;
  padding: 12px;
  border: 1px solid rgba(21, 105, 222, 0.12);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.94);
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  color: var(--app-ink);
  font-size: 14px;
  font-weight: 760;

  &::before {
    content: "";
    width: 3px;
    height: 13px;
    border-radius: 999px;
    background: var(--app-blue);
  }
}

.form-block-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.form-block {
  min-width: 0;
  padding: 10px;
  border: 1px solid rgba(21, 105, 222, 0.09);
  border-radius: 8px;
  background: rgba(247, 251, 255, 0.74);

  h3 {
    margin: 0 0 8px;
    color: var(--app-blue);
    font-size: 13px;
    font-weight: 720;
  }
}

.field-row {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 10px;
  color: var(--app-ink-soft);
  font-size: 12px;
  font-weight: 620;

  > span {
    line-height: 1.2;
    white-space: nowrap;
  }

  &:last-child {
    margin-bottom: 0;
  }

  /* 直接子选择器：结束时刻行里还嵌了「次日」复选框，不能套用整宽输入框样式 */
  > input {
    width: 100%;
    min-width: 0;
    height: 30px;
    padding: 0 9px;
    border: 1px solid rgba(21, 105, 222, 0.18);
    border-radius: 7px;
    background: #fff;
    color: var(--app-ink);
    font-family: var(--app-font-number);
    font-size: 12px;
    outline: none;
    transition: border-color 0.16s ease, box-shadow 0.16s ease;

    &:focus {
      border-color: rgba(21, 105, 222, 0.58);
      box-shadow: 0 0 0 3px rgba(21, 105, 222, 0.12);
    }
  }
}

.interval-note {
  margin: 10px 0 0;
  color: var(--app-muted);
  font-size: 12px;
  line-height: 1.5;
}

/* 方向块内的「早高峰 / 晚高峰 / 平峰」分档小标题 */
.sub-title {
  margin: 0 0 6px;
  padding-top: 8px;
  border-top: 1px dashed rgba(21, 105, 222, 0.16);
  color: var(--app-ink-soft);
  font-size: 12px;
  font-weight: 700;

  &:first-of-type {
    padding-top: 0;
    border-top: none;
  }
}

.link-button {
  margin-left: auto;
  padding: 0;
  border: none;
  background: none;
  color: var(--app-blue);
  font-size: 12px;
  font-weight: 620;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.field-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.next-day {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--app-muted);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;

  input {
    width: 12px;
    height: 12px;
    margin: 0;
    padding: 0;
    accent-color: var(--app-blue);
    cursor: pointer;
  }
}

/* ── 真实线路导入 ── */
.route-select {
  width: 100%;
}

.option-name {
  font-weight: 650;
}

.option-meta {
  margin-left: 8px;
  color: var(--app-muted);
  font-size: 12px;
}

.route-error {
  margin: 8px 0 0;
  color: #b42335;
  font-size: 12px;
  line-height: 1.5;
}

.route-picked {
  margin-top: 10px;
  padding: 9px 10px;
  border: 1px solid rgba(21, 105, 222, 0.14);
  border-radius: 8px;
  background: rgba(21, 105, 222, 0.05);
}

.route-picked-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.route-picked-name {
  color: var(--app-blue);
  font-size: 13px;
  font-weight: 720;
}

.swap-button {
  flex: 0 0 auto;
  padding: 3px 8px;
  border: 1px solid rgba(21, 105, 222, 0.28);
  border-radius: 999px;
  background: transparent;
  color: var(--app-blue);
  font-size: 11px;
  font-weight: 650;
  cursor: pointer;

  &:disabled {
    opacity: 0.45;
    cursor: not-allowed;
  }
}

.route-dir-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  color: var(--app-ink-soft);
  font-size: 12px;
  line-height: 1.4;
}

.dir-tag {
  flex: 0 0 auto;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(21, 105, 222, 0.14);
  color: var(--app-blue);
  font-size: 11px;
  font-style: normal;
  font-weight: 700;

  &.down {
    background: rgba(15, 139, 98, 0.14);
    color: #0f8b62;
  }
}

.route-missing {
  margin-top: 10px;
  padding: 9px 10px;
  border: 1px solid rgba(217, 138, 12, 0.32);
  border-radius: 8px;
  background: rgba(217, 138, 12, 0.09);
}

.route-missing-title {
  color: #a9670a;
  font-size: 12px;
  font-weight: 720;
}

.route-missing-group {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 6px;
}

.route-missing-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;

  span {
    padding: 2px 7px;
    border-radius: 999px;
    background: rgba(217, 138, 12, 0.18);
    color: #8a5407;
    font-size: 11px;
    font-weight: 650;
  }
}

.route-note {
  margin: 6px 0 0;
  color: var(--app-muted);
  font-size: 12px;
  line-height: 1.5;
}

/* 真实数据里为空、需要人工补的输入框统一用琥珀色描边标出 */
.form-block.needs-input {
  border-color: rgba(217, 138, 12, 0.42);
  background: rgba(217, 138, 12, 0.06);
}

.field-row.needs-input > input,
.form-block.needs-input .field-row > input {
  border-color: rgba(217, 138, 12, 0.5);
  background: rgba(217, 138, 12, 0.05);
}

.hint-text {
  margin: 10px 0 0;
  color: var(--app-muted);
  font-size: 12px;
  line-height: 1.5;
}

.table-wrap {
  max-height: 250px;
  overflow: auto;
  border: 1px solid rgba(21, 105, 222, 0.12);
  border-radius: 8px;
  background: #fff;

  &.compact {
    max-height: 230px;
  }
}

table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 12px;
}

th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 8px 6px;
  color: var(--app-muted);
  background: #f2f7fc;
  border-bottom: 1px solid rgba(21, 105, 222, 0.12);
  font-weight: 720;
  white-space: nowrap;
}

td {
  padding: 7px 6px;
  border-bottom: 1px solid rgba(21, 105, 222, 0.08);
  color: var(--app-ink-soft);
  text-align: center;
  vertical-align: middle;
  word-break: break-word;
}

tbody tr:hover td {
  background: rgba(21, 105, 222, 0.04);
}

.small {
  color: #0f8b62;
  font-weight: 760;
}

.vehicle-detail-table {
  table-layout: auto;
}

.time-list {
  text-align: left;
  white-space: nowrap;

  span {
    display: inline-block;
    margin: 0 10px 0 0;
    white-space: nowrap;

    &:last-child {
      margin-right: 0;
    }
  }
}

.diagram-section {
  position: relative;
}

.diagram-wrap {
  position: relative;
  overflow: hidden;
  border: 1px solid rgba(21, 105, 222, 0.12);
  border-radius: 8px;
  background: #fff;
}

canvas {
  display: block;
  width: 100%;
  height: auto;
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  margin-top: 10px;
  color: var(--app-ink-soft);
  font-size: 12px;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;

  i {
    width: 12px;
    height: 12px;
    border-radius: 3px;
  }
}

.note-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  margin-top: 8px;
  color: var(--app-muted);
  font-size: 12px;
}

.point-tooltip {
  position: absolute;
  z-index: 2;
  transform: translate(-50%, calc(-100% - 8px));
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(20, 35, 52, 0.88);
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
  pointer-events: none;
}

@media (max-width: 1100px) {
  .summary-strip,
  .workbench-grid,
  .form-block-grid {
    grid-template-columns: 1fr;
  }
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
/* 仅覆盖上文写死的浅色字面量；var(--app-*) 令牌已在 main.scss 随主题翻转。
   车辆轨迹图例色块（COLORS）与深底白字 tooltip 保持原样。 */
html.dark .panel-header {
  background: rgba(64, 156, 255, 0.11);
  border-bottom-color: rgba(64, 156, 255, 0.18);
}
html.dark .title-mark {
  box-shadow: 0 4px 10px rgba(64, 156, 255, 0.26);
}
html.dark .panel-body {
  background: rgba(13, 19, 27, 0.78);
}
html.dark .summary-strip.error {
  border-color: rgba(248, 113, 113, 0.3);
  background: rgba(248, 113, 113, 0.12);
}
html.dark .summary-card {
  border-color: rgba(64, 156, 255, 0.16);
  background: #151d27;
}
html.dark .summary-card.primary {
  border-color: rgba(64, 156, 255, 0.28);
  background: rgba(64, 156, 255, 0.1);
}
html.dark .error-title {
  color: #f87171;
}
html.dark .error-text {
  color: rgba(248, 113, 113, 0.82);
}
html.dark .form-section,
html.dark .result-section {
  border-color: rgba(64, 156, 255, 0.16);
  background: rgba(20, 27, 37, 0.94);
}
html.dark .form-block {
  border-color: rgba(64, 156, 255, 0.13);
  background: rgba(16, 22, 30, 0.74);
}
html.dark .field-row > input {
  border-color: rgba(64, 156, 255, 0.22);
  background: #1a2431;
}
html.dark .field-row > input:focus {
  border-color: rgba(64, 156, 255, 0.62);
  box-shadow: 0 0 0 3px rgba(64, 156, 255, 0.16);
}
html.dark .route-picked {
  border-color: rgba(64, 156, 255, 0.2);
  background: rgba(64, 156, 255, 0.08);
}
html.dark .dir-tag {
  background: rgba(64, 156, 255, 0.2);
}
html.dark .dir-tag.down {
  background: rgba(76, 205, 118, 0.18);
  color: #4ccd76;
}
html.dark .swap-button {
  border-color: rgba(64, 156, 255, 0.36);
}
html.dark .route-error {
  color: #f87171;
}
html.dark .route-missing {
  border-color: rgba(232, 168, 56, 0.34);
  background: rgba(232, 168, 56, 0.12);
}
html.dark .route-missing-title {
  color: #e8a838;
}
html.dark .route-missing-tags span {
  background: rgba(232, 168, 56, 0.2);
  color: #e8a838;
}
html.dark .form-block.needs-input {
  border-color: rgba(232, 168, 56, 0.4);
  background: rgba(232, 168, 56, 0.08);
}
html.dark .field-row.needs-input > input,
html.dark .form-block.needs-input .field-row > input {
  border-color: rgba(232, 168, 56, 0.45);
  background: rgba(232, 168, 56, 0.08);
}
html.dark .sub-title {
  border-top-color: rgba(64, 156, 255, 0.2);
}
html.dark .table-wrap {
  border-color: rgba(64, 156, 255, 0.16);
  background: #151d27;
}
html.dark th {
  background: #1a2431;
  border-bottom-color: rgba(64, 156, 255, 0.16);
}
html.dark td {
  border-bottom-color: rgba(64, 156, 255, 0.12);
}
html.dark tbody tr:hover td {
  background: rgba(64, 156, 255, 0.08);
}
html.dark .small {
  color: #4ccd76;
}
html.dark .diagram-wrap {
  border-color: rgba(64, 156, 255, 0.16);
  background: #151d27;
}
</style>
