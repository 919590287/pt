<template>
  <div ref="panelRef" :style="panelStyle" class="vehicle-calc-panel">
    <div ref="handleRef" class="panel-header">
      <div class="header-title">
        <span class="title-mark"></span>
        <div>
          <div class="title-main">配车测算</div>
          <div class="title-sub">公交线路发班与车辆配置测算</div>
        </div>
      </div>
      <div class="header-actions">
        <el-button size="small" @click.stop="resetExample">恢复示例</el-button>
        <el-button size="small" type="primary" @click.stop="generateTimetable">生成测算</el-button>
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
            <div class="section-title">服务时间设置</div>
            <div class="form-block-grid">
              <div class="form-block">
                <h3>上行服务时间</h3>
                <label class="field-row">
                  <span>开始</span>
                  <input v-model="form.upServiceStart" type="time" />
                </label>
                <label class="field-row">
                  <span>结束</span>
                  <input v-model="form.upServiceEnd" type="time" />
                </label>
              </div>
              <div class="form-block">
                <h3>下行服务时间</h3>
                <label class="field-row">
                  <span>开始</span>
                  <input v-model="form.downServiceStart" type="time" />
                </label>
                <label class="field-row">
                  <span>结束</span>
                  <input v-model="form.downServiceEnd" type="time" />
                </label>
              </div>
            </div>
          </section>

          <section class="form-section">
            <div class="section-title">高峰时段与发车间隔</div>
            <div class="form-block-grid">
              <div class="form-block">
                <h3>早高峰</h3>
                <label class="field-row">
                  <span>开始</span>
                  <input v-model="form.amStart" type="time" />
                </label>
                <label class="field-row">
                  <span>结束</span>
                  <input v-model="form.amEnd" type="time" />
                </label>
              </div>
              <div class="form-block">
                <h3>晚高峰</h3>
                <label class="field-row">
                  <span>开始</span>
                  <input v-model="form.pmStart" type="time" />
                </label>
                <label class="field-row">
                  <span>结束</span>
                  <input v-model="form.pmEnd" type="time" />
                </label>
              </div>
            </div>
            <div class="interval-grid">
              <label class="field-row">
                <span>高峰间隔 (分)</span>
                <input v-model.number="form.peakInterval" min="1" step="1" type="number" />
              </label>
              <label class="field-row">
                <span>平峰间隔 (分)</span>
                <input v-model.number="form.offInterval" min="1" step="1" type="number" />
              </label>
            </div>
          </section>

          <section class="form-section">
            <div class="section-title">车辆调度参数</div>
            <div class="form-block-grid">
              <div class="form-block">
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
import { nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useDraggable } from "@vueuse/core";

const panelRef = ref(null);
const handleRef = ref(null);
const canvasRef = ref(null);
const diagramWrapRef = ref(null);

const { style: panelStyle, x: panelX, y: panelY } = useDraggable(panelRef, {
  initialValue: { x: 20, y: 104 },
  handle: handleRef,
});

const PANEL_EDGE_GUTTER = 20;
const PANEL_TOP_GUTTER = 84;

function getPanelScale() {
  const panel = panelRef.value;
  const panelScale = panel ? Number.parseFloat(window.getComputedStyle(panel).scale) : Number.NaN;
  if (Number.isFinite(panelScale) && panelScale > 0) return panelScale;

  const rootScale = Number.parseFloat(window.getComputedStyle(document.documentElement).getPropertyValue("--app-panel-scale"));
  return Number.isFinite(rootScale) && rootScale > 0 ? rootScale : 1;
}

function centerPanel() {
  if (typeof window === "undefined") return;
  nextTick(() => {
    const panel = panelRef.value;
    if (!panel) return;

    const scale = getPanelScale();
    const visualWidth = panel.offsetWidth * scale;
    const visualHeight = panel.offsetHeight * scale;
    panelX.value = Math.max(PANEL_EDGE_GUTTER, (window.innerWidth - visualWidth) / 2);
    panelY.value = Math.max(PANEL_TOP_GUTTER, (window.innerHeight - visualHeight) / 2);
  });
}

const DEFAULT_FORM = Object.freeze({
  upServiceStart: "05:30",
  upServiceEnd: "23:00",
  downServiceStart: "05:30",
  downServiceEnd: "23:00",
  amStart: "07:00",
  amEnd: "09:00",
  pmStart: "17:00",
  pmEnd: "19:00",
  peakInterval: 30,
  offInterval: 60,
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

function validateParams() {
  if (!form.amStart || !form.amEnd || !form.pmStart || !form.pmEnd) {
    return "请完整填写高峰时段";
  }
  if (toNumber(form.peakInterval) < 1 || toNumber(form.offInterval) < 1) {
    return "发车间隔必须为大于等于 1 的整数";
  }
  const amStart = timeToMinutes(form.amStart);
  const amEnd = timeToMinutes(form.amEnd);
  const pmStart = timeToMinutes(form.pmStart);
  const pmEnd = timeToMinutes(form.pmEnd);
  if (amEnd <= amStart) return "早高峰结束时间必须晚于开始时间";
  if (pmEnd <= pmStart) return "晚高峰结束时间必须晚于开始时间";
  if (amEnd > pmStart) return "早高峰结束时间不能晚于晚高峰开始时间";
  if (timeToMinutes(form.upServiceEnd) <= timeToMinutes(form.upServiceStart)) {
    return "上行：服务结束时间必须晚于开始时间";
  }
  if (timeToMinutes(form.downServiceEnd) <= timeToMinutes(form.downServiceStart)) {
    return "下行：服务结束时间必须晚于开始时间";
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
    serviceStart: direction === "up" ? form.upServiceStart : form.downServiceStart,
    serviceEnd: direction === "up" ? form.upServiceEnd : form.downServiceEnd,
    amStart: form.amStart,
    amEnd: form.amEnd,
    amInterval: toNumber(form.peakInterval),
    pmStart: form.pmStart,
    pmEnd: form.pmEnd,
    pmInterval: toNumber(form.peakInterval),
    offInterval: toNumber(form.offInterval),
  };
}

function generateDirectionTimeline(params) {
  const serviceStart = timeToMinutes(params.serviceStart);
  const serviceEnd = timeToMinutes(params.serviceEnd);
  const amStart = timeToMinutes(params.amStart);
  const amEnd = timeToMinutes(params.amEnd);
  const pmStart = timeToMinutes(params.pmStart);
  const pmEnd = timeToMinutes(params.pmEnd);
  const effectiveAmStart = Math.max(amStart, serviceStart);
  const effectiveAmEnd = Math.min(amEnd, serviceEnd);
  const effectivePmStart = Math.max(pmStart, serviceStart);
  const effectivePmEnd = Math.min(pmEnd, serviceEnd);

  const periods = [];
  let current = serviceStart;
  if (effectiveAmEnd > effectiveAmStart) {
    if (current < effectiveAmStart) {
      periods.push({ start: current, end: effectiveAmStart, interval: params.offInterval });
    }
    periods.push({ start: effectiveAmStart, end: effectiveAmEnd, interval: params.amInterval });
    current = effectiveAmEnd;
  }
  if (effectivePmEnd > effectivePmStart) {
    if (current < effectivePmStart) {
      periods.push({ start: current, end: effectivePmStart, interval: params.offInterval });
    }
    periods.push({ start: effectivePmStart, end: effectivePmEnd, interval: params.pmInterval });
    current = effectivePmEnd;
  }
  if (current < serviceEnd) {
    periods.push({ start: current, end: serviceEnd, interval: params.offInterval });
  }

  const times = [];
  let cursor = serviceStart;
  let iteration = 0;
  while (cursor < serviceEnd && iteration < 1000) {
    times.push(cursor);
    let bestNext = null;
    for (const period of periods) {
      const candidate = cursor + period.interval;
      if (candidate >= period.start && candidate < period.end && candidate < serviceEnd) {
        if (bestNext === null || candidate < bestNext) bestNext = candidate;
      }
    }
    if (bestNext === null) {
      let nextStart = null;
      for (const period of periods) {
        if (period.start > cursor && (nextStart === null || period.start < nextStart)) {
          nextStart = period.start;
        }
      }
      if (nextStart !== null && nextStart < serviceEnd) bestNext = nextStart;
      else break;
    }
    cursor = bestNext;
    iteration += 1;
  }
  return times;
}

function scheduleVehiclesOnly(upTimes, downTimes, upDur, downDur, turnTime, error, upLength, downLength, largeRange, smallRange) {
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
  result.peakRoundTripVehicles = Math.ceil((toNumber(form.upDuration) + toNumber(form.downDuration) + 2 * toNumber(form.turnTime)) / toNumber(form.peakInterval));
  result.timetableRows = buildTimetableRows(upTimes, downTimes, schedule);
  result.note = `上行首班：${upTimes.length ? minutesToTime(upTimes[0]) : "无"}，下行首班：${downTimes.length ? minutesToTime(downTimes[0]) : "无"}，允许晚点 ≤ ${toNumber(form.errorMargin)} 分钟`;
  nextTick(drawVehicleDiagram);
}

function resetExample() {
  Object.assign(form, DEFAULT_FORM);
  generateTimetable();
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

  ctx.lineWidth = 0.5;
  for (let index = 0; index <= 10; index += 1) {
    const time = minTime + (index / 10) * timeRange;
    const xPos = x(time);
    ctx.beginPath();
    ctx.moveTo(xPos, marginTop);
    ctx.lineTo(xPos, height - marginBottom);
    ctx.strokeStyle = "#dce7f2";
    ctx.stroke();
  }

  ctx.strokeStyle = "#9eb6cc";
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  ctx.moveTo(marginLeft, y("A"));
  ctx.lineTo(width - marginRight, y("A"));
  ctx.moveTo(marginLeft, y("B"));
  ctx.lineTo(width - marginRight, y("B"));
  ctx.stroke();

  ctx.fillStyle = "#1569de";
  ctx.font = "700 18px system-ui, sans-serif";
  ctx.textAlign = "right";
  ctx.textBaseline = "top";
  ctx.fillText("首站", marginLeft - 8, y("A"));
  ctx.textBaseline = "bottom";
  ctx.fillText("末站", marginLeft - 8, y("B"));

  ctx.fillStyle = "#5f7083";
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
    ctx.strokeStyle = "#8796a6";
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

  ctx.fillStyle = "#22364c";
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

onMounted(() => {
  generateTimetable();
  centerPanel();
  window.addEventListener("resize", centerPanel);
});

onUnmounted(() => {
  window.removeEventListener("resize", centerPanel);
});
</script>

<style lang="scss" scoped>
.vehicle-calc-panel {
  position: fixed;
  z-index: var(--z-panel);
  width: min(1680px, calc((100vw - 40px) / var(--app-panel-scale)));
  max-height: calc((100vh - 132px) / var(--app-panel-scale));
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: var(--app-ink);
  background: var(--app-panel-bg);
  border: 1px solid var(--app-border);
  border-radius: var(--app-panel-radius);
  box-shadow: var(--app-shadow-panel);
  scale: var(--app-panel-scale);
  transform-origin: top left;
}

.panel-header {
  flex: 0 0 auto;
  min-height: 54px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  padding: 10px 14px;
  cursor: grab;
  user-select: none;
  background: rgba(21, 105, 222, 0.07);
  border-bottom: 1px solid rgba(21, 105, 222, 0.14);

  &:active {
    cursor: grabbing;
  }
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

  input {
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

.interval-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  padding-top: 10px;
  margin-top: 10px;
  border-top: 1px dashed rgba(21, 105, 222, 0.16);
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
  .vehicle-calc-panel {
    width: min(760px, calc((100vw - 40px) / var(--app-panel-scale)));
  }

  .summary-strip,
  .workbench-grid,
  .form-block-grid,
  .interval-grid {
    grid-template-columns: 1fr;
  }
}
</style>
