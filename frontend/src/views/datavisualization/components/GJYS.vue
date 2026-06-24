<!-- 轨迹演示 -->
<template>
  <div class="GJYS" v-bind="$attrs" v-show="!runMonitorPanels">
    <MCard class="card" wrap-body-class="body">
      <template #title>
        <div class="title">轨迹演示控制</div>
      </template>
      <template #body>
        <div v-if="loading && !trajectoryData" class="loading-state">
          <el-skeleton :rows="4" animated />
        </div>
        <el-empty v-else-if="loadError && !trajectoryData" :description="loadError" />
        <div v-else-if="isGenerating" class="build-state">
          <div class="build-title">{{ cacheMessage }}</div>
          <el-progress :percentage="buildProgressPercent" :stroke-width="8" :show-text="false" />
          <div class="build-metrics">
            <span>车辆 {{ formatNumber(progressInfo.vehicleCount) }}</span>
            <span>轨迹点 {{ formatNumber(progressInfo.pointCount) }}</span>
          </div>
        </div>
        <template v-else>
          <div class="control-row">
            <span class="label">播放状态</span>
            <div class="btn-group">
              <el-button-group>
                <el-button type="primary" :disabled="!canControl" @click="togglePlay">
                  <span>{{ isPlaying ? "暂停" : "播放" }}</span>
                </el-button>
                <el-button type="info" :disabled="!canControl" @click="resetPlayback">重置</el-button>
              </el-button-group>
            </div>
          </div>

          <div class="control-row mt-4">
            <span class="label">演示速度</span>
            <el-radio-group v-model="playSpeed" size="small" :disabled="!canControl" @change="changeSpeed">
              <el-radio-button :value="1">1x</el-radio-button>
              <el-radio-button :value="5">5x</el-radio-button>
              <el-radio-button :value="10">10x</el-radio-button>
              <el-radio-button :value="50">50x</el-radio-button>
            </el-radio-group>
          </div>

          <div class="control-row mt-4 flex-col">
            <div class="slider-header">
              <span class="label">时间进度</span>
              <span class="time-text">{{ formatTime(currentTime) }}</span>
            </div>
            <el-slider
              v-model="currentTime"
              :min="timeRange.min"
              :max="timeRange.max"
              :disabled="!canControl"
              :format-tooltip="formatTime"
              @input="handleSliderInput"
              @change="handleSliderCommit"
            />
          </div>

        </template>
      </template>
    </MCard>

    <MCard class="card mt-4" wrap-body-class="body">
      <template #title>
        <div class="title">车辆与状态监控</div>
      </template>
      <template #body>
        <div class="stat-grid">
          <div class="stat-item">
            <div class="stat-label">运行中车辆</div>
            <div class="stat-value text-primary">{{ activeVehicles }} <span class="unit">辆</span></div>
          </div>
          <div class="stat-item">
            <div class="stat-label">公交车</div>
            <div class="stat-value mode-bus">{{ activeByMode.bus }} <span class="unit">辆</span></div>
          </div>
          <div class="stat-item">
            <div class="stat-label">地铁</div>
            <div class="stat-value mode-subway">{{ activeByMode.subway }} <span class="unit">辆</span></div>
          </div>
          <div class="stat-item">
            <div class="stat-label">私家车</div>
            <div class="stat-value mode-car">{{ activeByMode.car }} <span class="unit">辆</span></div>
          </div>
          <div class="stat-item">
            <div class="stat-label">累计乘车人数</div>
            <div class="stat-value text-success">{{ cumulativePassengers }} <span class="unit">人次</span></div>
          </div>
          <div class="stat-item">
            <div class="stat-label">平均车速</div>
            <div class="stat-value text-warning">{{ avgSpeed }} <span class="unit">km/h</span></div>
          </div>
        </div>
      </template>
    </MCard>
  </div>

  <teleport to="#run-monitor-vehicle-controls" defer v-if="runMonitorPanels">
    <div class="run-gjys-control-panel">
      <div class="run-control-title">轨迹演示控制</div>
      <div v-if="loading && !trajectoryData" class="loading-state">
        <el-skeleton :rows="4" animated />
      </div>
      <el-empty v-else-if="loadError && !trajectoryData" :description="loadError" />
      <div v-else-if="isGenerating" class="build-state">
        <div class="build-title">{{ cacheMessage }}</div>
        <el-progress :percentage="buildProgressPercent" :stroke-width="8" :show-text="false" />
        <div class="build-metrics">
          <span>车辆 {{ formatNumber(progressInfo.vehicleCount) }}</span>
          <span>轨迹点 {{ formatNumber(progressInfo.pointCount) }}</span>
        </div>
      </div>
      <template v-else>
        <div class="control-row flex-col">
          <span class="label">播放状态</span>
          <div class="btn-group">
            <el-button-group>
              <el-button type="primary" :disabled="!canControl" @click="togglePlay">
                <span>{{ isPlaying ? "暂停" : "播放" }}</span>
              </el-button>
              <el-button type="info" :disabled="!canControl" @click="resetPlayback">重置</el-button>
            </el-button-group>
          </div>
        </div>

        <div class="control-row flex-col">
          <span class="label">演示速度</span>
          <el-radio-group v-model="playSpeed" size="small" :disabled="!canControl" @change="changeSpeed">
            <el-radio-button :value="1">1x</el-radio-button>
            <el-radio-button :value="5">5x</el-radio-button>
            <el-radio-button :value="10">10x</el-radio-button>
            <el-radio-button :value="50">50x</el-radio-button>
          </el-radio-group>
        </div>

        <div class="control-row flex-col">
          <div class="slider-header">
            <span class="label">时间进度</span>
            <span class="time-text">{{ formatTime(currentTime) }}</span>
          </div>
          <el-slider
            v-model="currentTime"
            :min="timeRange.min"
            :max="timeRange.max"
            :disabled="!canControl"
            :format-tooltip="formatTime"
            @input="handleSliderInput"
            @change="handleSliderCommit"
          />
        </div>
      </template>
    </div>
  </teleport>

  <teleport to="#datavisualization_index_box2" defer v-if="runMonitorPanels && !vehiclePanelInfo">
    <MCard2 class="GJYS_right_card vehicle-status-right-card" title="车辆与状态监控" :open="true">
      <template #body>
        <div class="rm-veh-hero">
          <div class="rm-veh-hero-head">
            <span class="rm-veh-hero-label">实时运行车辆</span>
            <span class="rm-veh-live"><span class="rm-veh-live-dot"></span>实时</span>
          </div>
          <div class="rm-veh-hero-value">{{ activeVehicles }}<span class="rm-veh-hero-unit">辆</span></div>
          <div class="rm-veh-modes">
            <div class="rm-veh-mode bus">
              <span class="rm-veh-mode-name">公交车</span>
              <span class="rm-veh-mode-val">{{ activeByMode.bus }}</span>
            </div>
            <div class="rm-veh-mode subway">
              <span class="rm-veh-mode-name">地铁</span>
              <span class="rm-veh-mode-val">{{ activeByMode.subway }}</span>
            </div>
            <div class="rm-veh-mode car">
              <span class="rm-veh-mode-name">私家车</span>
              <span class="rm-veh-mode-val">{{ activeByMode.car }}</span>
            </div>
          </div>
        </div>
        <div class="rm-veh-stats">
          <div class="rm-veh-stat">
            <div class="rm-veh-stat-label">累计乘车人数</div>
            <div class="rm-veh-stat-value success">{{ cumulativePassengers }}<span class="unit">人次</span></div>
          </div>
          <div class="rm-veh-stat">
            <div class="rm-veh-stat-label">平均车速</div>
            <div class="rm-veh-stat-value warning">{{ avgSpeed }}<span class="unit">km/h</span></div>
          </div>
        </div>
      </template>
    </MCard2>
  </teleport>

  <teleport to="#datavisualization_index_box2" defer v-if="vehiclePanelInfo">
    <MCard2 class="GJYS_right_card" title="车辆信息" :open="true">
      <template #body>
        <div class="vehicle-panel">
          <div class="vehicle-head">
            <div>
              <div class="vehicle-id">{{ vehiclePanelInfo.id }}</div>
              <div class="vehicle-type">{{ vehiclePanelInfo.typeLabel }}</div>
            </div>
            <el-button type="primary" size="small" plain @click="clearVehicleFollow">解除跟随</el-button>
          </div>

          <div class="info-grid">
            <div class="info-item">
              <span class="label">当前速度</span>
              <span class="value">{{ vehiclePanelInfo.speed }}</span>
            </div>
            <template v-if="vehiclePanelInfo.isTransit">
              <div class="info-item">
                <span class="label">线路名称</span>
                <span class="value">{{ vehiclePanelInfo.lineName }}</span>
              </div>
              <div class="info-item">
                <span class="label">上一站</span>
                <span class="value">{{ vehiclePanelInfo.previousStation }}</span>
              </div>
              <div class="info-item">
                <span class="label">下一站</span>
                <span class="value">{{ vehiclePanelInfo.nextStation }}</span>
              </div>
              <div class="info-item">
                <span class="label">车内人员</span>
                <span class="value">{{ vehiclePanelInfo.passengerCount }}</span>
              </div>
              <div class="info-item">
                <span class="label">满载率</span>
                <span class="value">{{ vehiclePanelInfo.loadRate }}</span>
              </div>
              <div class="info-item wide">
                <span class="label">运营时段</span>
                <span class="value">{{ vehiclePanelInfo.schedule }}</span>
              </div>
            </template>
            <template v-else>
              <div class="info-item">
                <span class="label">车内人员</span>
                <span class="value">{{ vehiclePanelInfo.personCount }}</span>
              </div>
              <div class="info-item wide">
                <span class="label">出发地</span>
                <span class="value">{{ vehiclePanelInfo.origin }}</span>
              </div>
              <div class="info-item wide">
                <span class="label">目的地</span>
                <span class="value">{{ vehiclePanelInfo.destination }}</span>
              </div>
              <div class="info-item wide">
                <span class="label">出行目的</span>
                <span class="value">{{ vehiclePanelInfo.purpose }}</span>
              </div>
            </template>
          </div>

          <div class="stations-section" v-if="vehiclePanelInfo.isTransit">
            <div class="section-title">完整站序</div>
            <div class="station-list">
              <div
                v-for="station in vehiclePanelInfo.stops"
                :key="station.id || station.index"
                :class="['station-row', station.role]"
              >
                <span class="station-index">{{ station.index }}</span>
                <span class="station-name">{{ station.name }}</span>
              </div>
            </div>
          </div>
        </div>
      </template>
    </MCard2>
  </teleport>
</template>

<script setup>
import { computed, inject, onMounted, onUnmounted, ref, watch } from "vue";
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";
import { dataTrajectory, dataTrajectoryChunk, dataTrajectoryChunkBinary } from "@/api/trajectory.js";
import { VehicleTrajectoryLayer, VEHICLE_MODE_CONFIG, parseVehicleTrajectoryBinaryChunk } from "../layers/VehicleTrajectoryLayer.js";
import { getCachedChunk, putCachedChunk, pruneChunkCache } from "@/utils/trajectoryChunkCache.js";

const props = defineProps({
  model: String,
  runMonitorPanels: {
    type: Boolean,
    default: false,
  },
});

const MODE_KEYS = ["bus", "subway", "car"];

const MapRef = inject("MapRef");
const rightPanelHasContent = inject("rightPanelHasContent", ref(false));
const activeDatavisualizationTab = inject("activeDatavisualizationTab", ref(""));
const VehicleSizeRef = inject("VehicleSizeRef", ref(36));
const VehicleVisibilityModeRef = inject("VehicleVisibilityModeRef", ref("all"));
const DEFAULT_CHUNK_SECONDS = 300;
const PREFETCH_WINDOW_SECONDS = 120;
const MAX_CHUNK_CACHE = 7;
// 分块缓存按字节预算淘汰，避免大 events 下 7 个大分块常驻导致 OOM。
const MAX_CHUNK_CACHE_BYTES = 96 * 1024 * 1024;
// 播放时滑块/时间文本的刷新节流（图层仍按 rAF 每帧驱动，UI 不必每帧重渲染）。
const UI_SYNC_INTERVAL_MS = 120;
const SEEK_CHUNK_LOAD_DELAY_MS = 48;
const loading = ref(false);
const chunkLoading = ref(false);
const loadError = ref("");
const chunkError = ref("");
const cacheStatus = ref("idle");
const cacheMessage = ref("");
const progressInfo = ref({});
const isPlaying = ref(false);
const playSpeed = ref(10);
const currentTime = ref(28800);
const timeRange = ref({ min: 0, max: 86400 });
const trajectoryData = ref(null);
const currentChunkData = ref(null);
const cumulativePassengers = ref(0);
const cumulativeByMode = ref(emptyModeCount());
const liveStats = ref(emptyStats());
const followedVehicle = ref(null);

let trajectoryLayer = null;
let playbackFrame = null;
// 播放时钟（普通变量，不走 Vue 响应式）：每帧直接驱动图层，currentTime ref 仅按节流回写给滑块。
// 改为"实时锚点"驱动：simTime = anchorSim + (now-anchorReal)*speed。不再累加封顶增量，
// 卡顿掉帧也不会丢仿真时间，从根上消除"车辆正常运行→突然大幅降速→又正常"的抖动。
let playbackClock = 0;
let playbackAnchorReal = 0;
let playbackAnchorSim = 0;
let lastUiSyncAt = 0;
let lastStatsUiSyncAt = 0;
let lastPassengerUiSyncAt = 0;
// 拖动进度条到未缓存分块时的防抖加载，避免每次 input 都发请求/清空车辆造成闪烁与长时间空白。
let seekChunkTimer = null;
let pollTimer = null;
let loadSeq = 0;
let chunkSeq = 0;
let currentChunkStart = null;
let pendingChunkStart = null;
let chunkCache = new Map();
let prefetchingChunks = new Set();
let chunkRequests = new Map();
let passengerTimeIndex = emptyPassengerIndex();
let passengerSeriesRows = [];

const summary = computed(() => trajectoryData.value?.summary || {});
const hasTrajectory = computed(() => cacheStatus.value === "ready" && Number(summary.value.totalVehicles || 0) > 0);
const isTrajectoryMonitorActive = computed(() => activeDatavisualizationTab.value === "轨迹演示" || activeDatavisualizationTab.value === "车辆运行监测");
watch([followedVehicle, activeDatavisualizationTab, () => props.runMonitorPanels], () => {
  if (isTrajectoryMonitorActive.value) {
    rightPanelHasContent.value = props.runMonitorPanels || !!followedVehicle.value;
  }
}, { immediate: true });

const canControl = computed(() => hasTrajectory.value);
const isGenerating = computed(() => cacheStatus.value === "generating");
const chunkSeconds = computed(() => Math.max(60, Number(trajectoryData.value?.chunkSeconds || DEFAULT_CHUNK_SECONDS)));
const buildProgressPercent = computed(() => {
  const min = Number(progressInfo.value.minTime || 0);
  const max = Number(progressInfo.value.maxTime || 0);
  if (max <= min || max <= 0) return 8;
  const day = 24 * 3600;
  return Math.min(96, Math.max(8, Math.round((max / day) * 100)));
});
const activeVehicles = computed(() => liveStats.value.activeTotal || 0);
const activeByMode = computed(() => liveStats.value.activeByMode || emptyModeCount());
const avgSpeed = computed(() => (liveStats.value.avgSpeed || 0).toFixed(1));
const vehiclePanelInfo = computed(() => {
  const vehicle = followedVehicle.value;
  if (!vehicle || !isTrajectoryMonitorActive.value) return null;
  const meta = vehicle.meta || {};
  const mode = normalizeMode(meta.mode || vehicle.mode);
  const route = vehicle.route || {};
  const isTransit = mode === "bus" || mode === "subway";
  const stops = Array.isArray(route.stops) ? route.stops : [];
  const stationState = stationPair(vehicle, stops);
  const passengerCount = isTransit ? occupancyAt(meta.passengerEvents, currentTime.value) : 0;
  const capacity = Number(meta.capacity) || 0;
  const loadRate = capacity > 0 ? `${Math.min(999, (passengerCount / capacity) * 100).toFixed(1)}%` : "--";
  const personCount = Number(meta.personCount) || (Array.isArray(meta.personIds) ? meta.personIds.length : 0) || 1;

  return {
    id: meta.id || vehicle.id || vehicle.key || "--",
    typeLabel: modeLabel(mode),
    isTransit,
    lineName: meta.lineName || route.lineName || meta.lineId || "--",
    previousStation: stationState.previous?.name || "--",
    nextStation: stationState.next?.name || "--",
    speed: formatSpeed(vehicle.speed),
    passengerCount: `${passengerCount} 人`,
    loadRate,
    schedule: formatSchedule(meta.firstTime ?? route.firstTime, meta.lastTime ?? route.lastTime),
    stops: stops.map((station, index) => ({
      ...station,
      index: station.index || index + 1,
      role: stationState.previousIndex === index ? "previous" : stationState.nextIndex === index ? "next" : "",
    })),
    personCount: `${personCount} 人`,
    origin: formatLocation(meta.origin),
    destination: formatLocation(meta.destination),
    purpose: formatPurpose(meta.purpose ?? meta.destination?.type),
  };
});

function emptyModeCount() {
  return MODE_KEYS.reduce((result, mode) => {
    result[mode] = 0;
    return result;
  }, {});
}

function emptyPassengerIndex() {
  return {
    all: [],
    bus: [],
    subway: [],
    car: [],
  };
}

function emptyStats() {
  return {
    activeTotal: 0,
    activeByMode: emptyModeCount(),
    avgSpeed: 0,
    routeActive: {},
  };
}

function normalizeMode(mode) {
  return MODE_KEYS.includes(mode) ? mode : "car";
}

function buildPassengerIndex(events = []) {
  const index = emptyPassengerIndex();
  for (const event of events) {
    const time = Number(event[0]);
    if (!Number.isFinite(time)) continue;
    const mode = normalizeMode(event[1]);
    index.all.push(time);
    index[mode].push(time);
  }
  Object.values(index).forEach((list) => list.sort((a, b) => a - b));
  return index;
}

function buildPassengerSeriesRows(series = []) {
  let bus = 0;
  let subway = 0;
  let car = 0;
  let total = 0;
  return series
    .map((row) => ({
      time: Number(row[0]),
      busDelta: Number(row[1]) || 0,
      subwayDelta: Number(row[2]) || 0,
      carDelta: Number(row[3]) || 0,
      totalDelta: Number(row[4]) || 0,
    }))
    .filter((row) => Number.isFinite(row.time))
    .sort((a, b) => a.time - b.time)
    .map((row) => {
      bus += row.busDelta;
      subway += row.subwayDelta;
      car += row.carDelta;
      total += row.totalDelta;
      return { time: row.time, bus, subway, car, total };
    });
}

function countUntil(list, time) {
  let left = 0;
  let right = list.length;
  while (left < right) {
    const mid = Math.floor((left + right) / 2);
    if (list[mid] <= time) {
      left = mid + 1;
    } else {
      right = mid;
    }
  }
  return left;
}

function cumulativeAt(time) {
  if (!passengerSeriesRows.length) {
    return {
      total: countUntil(passengerTimeIndex.all, time),
      bus: countUntil(passengerTimeIndex.bus, time),
      subway: countUntil(passengerTimeIndex.subway, time),
      car: countUntil(passengerTimeIndex.car, time),
    };
  }
  let left = 0;
  let right = passengerSeriesRows.length;
  while (left < right) {
    const mid = Math.floor((left + right) / 2);
    if (passengerSeriesRows[mid].time <= time) {
      left = mid + 1;
    } else {
      right = mid;
    }
  }
  return passengerSeriesRows[Math.max(0, left - 1)] || { total: 0, bus: 0, subway: 0, car: 0 };
}

function occupancyAt(events = [], time) {
  if (!Array.isArray(events) || !events.length) return 0;
  let count = 0;
  for (const event of events) {
    const eventTime = Number(event?.[0]);
    if (!Number.isFinite(eventTime) || eventTime > time) break;
    count += Number(event?.[1]) || 0;
  }
  return Math.max(0, count);
}

function stationPair(vehicle, stops = []) {
  if (!stops.length) {
    return { previous: null, next: null, previousIndex: -1, nextIndex: -1 };
  }
  const [x, y] = vehicle.webMercator || [];
  let nearestIndex = 0;
  let nearestDistance = Infinity;
  if (Number.isFinite(Number(x)) && Number.isFinite(Number(y))) {
    stops.forEach((station, index) => {
      const sx = Number(station.x);
      const sy = Number(station.y);
      if (!Number.isFinite(sx) || !Number.isFinite(sy)) return;
      const distance = Math.hypot(Number(x) - sx, Number(y) - sy);
      if (distance < nearestDistance) {
        nearestDistance = distance;
        nearestIndex = index;
      }
    });
  }
  const previousIndex = nearestIndex >= stops.length - 1 ? Math.max(0, stops.length - 2) : nearestIndex;
  const nextIndex = Math.min(stops.length - 1, previousIndex + 1);
  return {
    previous: stops[previousIndex] || null,
    next: stops[nextIndex] || null,
    previousIndex,
    nextIndex,
  };
}

function initialTime(range = timeRange.value) {
  if (range.min <= 28800 && range.max >= 28800) {
    return 28800;
  }
  return range.min;
}

function clampTime(seconds) {
  return Math.min(timeRange.value.max, Math.max(timeRange.value.min, Number(seconds) || timeRange.value.min));
}

function chunkStartOf(seconds) {
  const step = chunkSeconds.value;
  return Math.max(0, Math.floor((Number(seconds) || 0) / step) * step);
}

function ensureTrajectoryLayer() {
  if (!MapRef?.value || trajectoryLayer) return;
  trajectoryLayer = new VehicleTrajectoryLayer({ zIndex: 1200, vehicleSize: VehicleSizeRef.value });
  trajectoryLayer.setVehicleMeta(trajectoryData.value?.meta || {});
  trajectoryLayer.setVehicleVisibilityMode(VehicleVisibilityModeRef.value);
  trajectoryLayer.setStatsCallback((stats) => {
    publishLiveStats(stats);
  });
  trajectoryLayer.setFollowCallback((vehicle) => {
    followedVehicle.value = vehicle;
  });
  MapRef.value.addLayer(trajectoryLayer);
  if (currentChunkData.value) {
    trajectoryLayer.setData(currentChunkData.value);
    syncStats();
  }
}

async function loadTrajectory() {
  const seq = ++loadSeq;
  stopPlayback();
  stopPolling();
  cancelSeekChunkLoad();
  trajectoryData.value = null;
  currentChunkData.value = null;
  currentChunkStart = null;
  pendingChunkStart = null;
  chunkCache = new Map();
  prefetchingChunks = new Set();
  chunkRequests = new Map();
  cacheStatus.value = "idle";
  cacheMessage.value = "";
  progressInfo.value = {};
  liveStats.value = emptyStats();
  followedVehicle.value = null;
  cumulativePassengers.value = 0;
  cumulativeByMode.value = emptyModeCount();
  passengerTimeIndex = emptyPassengerIndex();
  passengerSeriesRows = [];
  if (trajectoryLayer) {
    trajectoryLayer.setData(null);
  }
  if (!props.model) return;

  loading.value = true;
  loadError.value = "";
  chunkError.value = "";
  try {
    const res = await dataTrajectory({ datasource: props.model });
    if (seq !== loadSeq) return;

    const data = res.data || {};
    await applyTrajectoryStatus(data, seq);
  } catch (error) {
    if (seq !== loadSeq) return;
    loadError.value = "events轨迹数据加载失败";
  } finally {
    if (seq === loadSeq) {
      loading.value = false;
    }
  }
}

async function applyTrajectoryStatus(data, seq) {
  cacheStatus.value = data.status || "ready";
  cacheMessage.value = data.message || (cacheStatus.value === "generating" ? "正在生成轨迹缓存" : "");
  progressInfo.value = data.progress || {};
  trajectoryData.value = data;
  if (cacheStatus.value === "failed") {
    loadError.value = data.message || "events轨迹缓存生成失败";
  }

  const min = Number(data.timeRange?.min);
  const max = Number(data.timeRange?.max);
  timeRange.value = {
    min: Number.isFinite(min) ? min : 0,
    max: Number.isFinite(max) && max > min ? max : 86400,
  };

  passengerTimeIndex = buildPassengerIndex(data.passengerEvents || []);
  passengerSeriesRows = buildPassengerSeriesRows(data.passengerSeries || []);
  currentTime.value = initialTime(timeRange.value);
  ensureTrajectoryLayer();
  trajectoryLayer?.setVehicleMeta(data.meta || {});
  trajectoryLayer?.setVehicleVisibilityMode(VehicleVisibilityModeRef.value);

  if (cacheStatus.value === "ready") {
    // events 已确定：清理本模型其它版本的过期分块缓存（不阻塞首块加载）。
    pruneChunkCache(props.model, eventsTag());
    await loadChunkForTime(currentTime.value, seq, true);
  } else if (cacheStatus.value === "generating") {
    schedulePolling(seq);
  }
  syncStats();
}

function schedulePolling(seq) {
  stopPolling();
  pollTimer = window.setTimeout(async () => {
    if (seq !== loadSeq || !props.model) return;
    try {
      const res = await dataTrajectory({ datasource: props.model });
      if (seq !== loadSeq) return;
      await applyTrajectoryStatus(res.data || {}, seq);
    } catch (error) {
      if (seq === loadSeq) {
        loadError.value = "events轨迹缓存状态获取失败";
      }
    }
  }, 5000);
}

function stopPolling() {
  if (pollTimer) {
    window.clearTimeout(pollTimer);
    pollTimer = null;
  }
}

async function ensureChunkForTime(time) {
  if (!hasTrajectory.value || !props.model) return;
  const start = chunkStartOf(time);
  if (chunkCache.has(start)) {
    applyChunkData(start, chunkCache.get(start));
    return;
  }
  if (currentChunkStart === start && currentChunkData.value) return;
  if (pendingChunkStart === start) return;
  await loadChunkForTime(time, loadSeq, false);
}

async function loadChunkForTime(time, seq, force = false) {
  const start = chunkStartOf(time);
  if (!force && currentChunkStart === start && currentChunkData.value) return;
  if (!force && chunkCache.has(start)) {
    applyChunkData(start, chunkCache.get(start));
    return;
  }
  if (!force && pendingChunkStart === start) return;
  const myChunkSeq = ++chunkSeq;
  pendingChunkStart = start;
  chunkLoading.value = true;
  try {
    const data = await requestTrajectoryChunkOnce(start);
    if (seq !== loadSeq || myChunkSeq !== chunkSeq) return;
    if (data.status !== "ready") {
      await applyTrajectoryStatus(data, seq);
      return;
    }
    rememberChunk(start, data);
    chunkError.value = "";
    applyChunkData(start, data);
  } catch (error) {
    if (seq === loadSeq) {
      chunkError.value = "轨迹分块加载失败";
    }
  } finally {
    if (pendingChunkStart === start) {
      pendingChunkStart = null;
    }
    if (seq === loadSeq && myChunkSeq === chunkSeq) {
      chunkLoading.value = false;
    }
  }
}

function applyChunkData(start, data) {
  currentChunkStart = start;
  currentChunkData.value = data;
  trajectoryLayer?.setVehicleMeta(data?.meta || trajectoryData.value?.meta || {});
  trajectoryLayer?.setData(data);
  syncStats();
  prefetchAdjacentChunks(start);
  prefetchAroundTime(currentTime.value);
}

function rememberChunk(start, data) {
  if (!data || data.status !== "ready") return;
  chunkCache.set(start, data);
  trimChunkCache(start);
}

function chunkBytes(data) {
  const segments = data?.segments;
  if (segments && Number.isFinite(segments.byteLength)) return segments.byteLength;
  const count = Number(data?.segmentCount) || 0;
  const stride = Number(data?.stride) || 8;
  return count * stride * 4;
}

function trimChunkCache(anchorStart) {
  // 同时受条数与字节预算约束，淘汰离当前时间最远的分块；至少保留一个。
  const entries = [...chunkCache.keys()].sort(
    (a, b) => Math.abs(a - anchorStart) - Math.abs(b - anchorStart),
  );
  const keep = new Set();
  let totalBytes = 0;
  for (const key of entries) {
    const bytes = chunkBytes(chunkCache.get(key));
    const withinCount = keep.size < MAX_CHUNK_CACHE;
    const withinBytes = totalBytes + bytes <= MAX_CHUNK_CACHE_BYTES;
    if (keep.size === 0 || (withinCount && withinBytes)) {
      keep.add(key);
      totalBytes += bytes;
    }
  }
  for (const key of chunkCache.keys()) {
    if (!keep.has(key)) {
      chunkCache.delete(key);
    }
  }
}

function isChunkStartInRange(start) {
  return start >= chunkStartOf(timeRange.value.min) && start <= chunkStartOf(timeRange.value.max);
}

function prefetchAroundTime(time) {
  if (!hasTrajectory.value || !props.model) return;
  const start = chunkStartOf(time);
  const offset = Math.max(0, Number(time) - start);
  if (offset >= chunkSeconds.value - PREFETCH_WINDOW_SECONDS) {
    prefetchChunk(start + chunkSeconds.value, loadSeq);
  }
  if (offset <= PREFETCH_WINDOW_SECONDS) {
    prefetchChunk(start - chunkSeconds.value, loadSeq);
  }
}

function prefetchAdjacentChunks(start) {
  if (!hasTrajectory.value || !props.model) return;
  prefetchChunk(start + chunkSeconds.value, loadSeq);
  prefetchChunk(start - chunkSeconds.value, loadSeq);
}

async function prefetchChunk(start, seq) {
  if (!isChunkStartInRange(start) || chunkCache.has(start) || prefetchingChunks.has(start) || pendingChunkStart === start) {
    return;
  }
  prefetchingChunks.add(start);
  try {
    const data = await requestTrajectoryChunkOnce(start);
    if (seq === loadSeq && data?.status === "ready") {
      rememberChunk(start, data);
      // 预取的相邻分块同时推给 Worker 预建每秒索引并常驻，播放越过边界时即可秒切不卡（双缓冲）。
      trajectoryLayer?.preindexChunk(data);
    }
  } catch (error) {
    // Prefetch is opportunistic; the foreground loader will surface errors.
  } finally {
    prefetchingChunks.delete(start);
  }
}

async function requestTrajectoryChunkOnce(start) {
  if (chunkRequests.has(start)) {
    return chunkRequests.get(start);
  }
  const requestMap = chunkRequests;
  const promise = requestTrajectoryChunk(start)
    .finally(() => {
      if (requestMap.get(start) === promise) {
        requestMap.delete(start);
      }
    });
  requestMap.set(start, promise);
  return promise;
}

// 分块的本地缓存版本标识：events 变化（mod/size/cacheVersion 任一变化）即失效。
function eventsTag() {
  const manifest = trajectoryData.value || {};
  return `${manifest.cacheVersion || ""}:${manifest.eventsModified || ""}:${manifest.eventsSize || ""}`;
}

function chunkCacheKey(start) {
  if (!props.model) return "";
  return `${props.model}::${eventsTag()}::${start}`;
}

async function requestTrajectoryChunk(start) {
  const cacheKey = chunkCacheKey(start);
  // 1) 先查本地持久缓存（IndexedDB）：命中即零网络、跨会话直读。
  if (cacheKey) {
    try {
      const cached = await getCachedChunk(cacheKey);
      if (cached && cached.byteLength) {
        return parseVehicleTrajectoryBinaryChunk(cached, trajectoryData.value || {});
      }
    } catch (error) {
      // 本地缓存损坏则回退网络。
    }
  }

  // 2) 走可缓存的 GET 二进制端点；成功后把原始字节写入 IndexedDB（结构化克隆，不影响已解析视图）。
  try {
    const res = await dataTrajectoryChunkBinary({ datasource: props.model }, start);
    if (res?.status === 200 && res.data?.byteLength) {
      const parsed = parseVehicleTrajectoryBinaryChunk(res.data, trajectoryData.value || {});
      if (cacheKey) {
        putCachedChunk(cacheKey, res.data, { ds: props.model, ver: eventsTag() }).catch(() => {});
      }
      return parsed;
    }
  } catch (error) {
    // JSON keeps the feature usable when an old cache or browser rejects the binary path.
  }

  const res = await dataTrajectoryChunk({ datasource: props.model }, start);
  return res.data || {};
}

function publishLiveStats(stats, force = false) {
  const now = performance.now();
  if (!force && isPlaying.value && now - lastStatsUiSyncAt < UI_SYNC_INTERVAL_MS) return;
  lastStatsUiSyncAt = now;
  liveStats.value = stats || emptyStats();
}

function syncStatsAt(time, force = !isPlaying.value) {
  const layerStats = trajectoryLayer?.setTime(time) || emptyStats();
  publishLiveStats(layerStats, force);
  syncPassengerStatsAt(time, force);
}

function syncPassengerStatsAt(time, force = !isPlaying.value) {
  const now = performance.now();
  if (!force && isPlaying.value && now - lastPassengerUiSyncAt < UI_SYNC_INTERVAL_MS) return;
  lastPassengerUiSyncAt = now;
  const passengerCounts = cumulativeAt(time);
  cumulativePassengers.value = passengerCounts.total || 0;
  cumulativeByMode.value = {
    bus: passengerCounts.bus || 0,
    subway: passengerCounts.subway || 0,
    car: passengerCounts.car || 0,
  };
}

function syncStats() {
  syncStatsAt(currentTime.value, true);
}

// 把任意时刻应用到图层：必要时切换分块（命中缓存即时切换，否则异步加载），再按该时刻采样。
function driveLayerTime(time) {
  const start = chunkStartOf(time);
  if (start !== currentChunkStart) {
    if (chunkCache.has(start)) {
      applyChunkData(start, chunkCache.get(start));
    } else {
      ensureChunkForTime(time);
      prefetchAdjacentChunks(start);
      syncPassengerStatsAt(time);
      return;
    }
  } else {
    prefetchAroundTime(time);
  }
  syncStatsAt(time);
}

// 暂停状态下的拖动/跳转：命中缓存即时切换；未缓存则先在当前分块采样（保留车辆），并防抖加载目标分块。
function seekToTime(time) {
  const start = chunkStartOf(time);
  if (start !== currentChunkStart) {
    if (chunkCache.has(start)) {
      applyChunkData(start, chunkCache.get(start));
      return;
    }
    syncPassengerStatsAt(time);
    prefetchAdjacentChunks(start);
    scheduleSeekChunkLoad(time);
    return;
  }
  prefetchAroundTime(time);
  syncStatsAt(time);
}

function scheduleSeekChunkLoad(time) {
  if (seekChunkTimer) window.clearTimeout(seekChunkTimer);
  seekChunkTimer = window.setTimeout(() => {
    seekChunkTimer = null;
    ensureChunkForTime(time);
  }, SEEK_CHUNK_LOAD_DELAY_MS);
}

function cancelSeekChunkLoad() {
  if (seekChunkTimer) {
    window.clearTimeout(seekChunkTimer);
    seekChunkTimer = null;
  }
}

function togglePlay() {
  if (!canControl.value) return;
  isPlaying.value = !isPlaying.value;
  if (isPlaying.value) {
    startPlayback();
  } else {
    stopPlayback();
    // 暂停后把滑块/时间对齐到内部时钟的精确位置（watch 因 isPlaying=false 会做一次落点采样）。
    currentTime.value = clampTime(playbackClock);
  }
}

function resetPlayback() {
  isPlaying.value = false;
  stopPlayback();
  cancelSeekChunkLoad();
  const time = initialTime();
  anchorPlayback(time);
  currentTime.value = time;
  ensureChunkForTime(time);
  syncStatsAt(time);
}

function changeSpeed() {
  // 变速时以当前时刻重锚，避免把新倍速错误地应用到已过去的真实时长上（否则会瞬跳）。
  if (isPlaying.value) {
    anchorPlayback(playbackClock);
  }
}

// 用当前真实时刻把仿真时钟重新锚定到 simTime，之后每帧由 (now-anchorReal)*speed 推算，不累加误差。
function anchorPlayback(simTime, now = performance.now()) {
  playbackAnchorSim = clampTime(simTime);
  playbackAnchorReal = now;
  playbackClock = playbackAnchorSim;
}

function startPlayback() {
  stopPlayback();
  cancelSeekChunkLoad();
  const startedAt = performance.now();
  lastUiSyncAt = startedAt;
  anchorPlayback(currentTime.value, startedAt);
  const tick = (now) => {
    if (!isPlaying.value) return;
    // 仿真时刻只由真实经过时间推算；某帧卡顿后下一帧直接落到正确时刻，不会"慢一截再追"。
    const target = playbackAnchorSim + Math.max(0, (now - playbackAnchorReal) / 1000) * playSpeed.value;
    if (target > timeRange.value.max) {
      // 播放到末尾：回到起点并重锚，保持匀速循环。
      anchorPlayback(timeRange.value.min, now);
    } else {
      playbackClock = target;
    }
    // 每帧直接驱动图层（采样在 Worker 中进行），实现秒级流畅。
    driveLayerTime(playbackClock);
    // 滑块/时间文本按节流回写，避免每帧触发 Vue 重渲染与重复 setTime。
    if (now - lastUiSyncAt >= UI_SYNC_INTERVAL_MS && currentTime.value !== playbackClock) {
      lastUiSyncAt = now;
      currentTime.value = playbackClock;
    }
    playbackFrame = window.requestAnimationFrame(tick);
  };
  playbackFrame = window.requestAnimationFrame(tick);
}

function stopPlayback() {
  if (playbackFrame) {
    window.cancelAnimationFrame(playbackFrame);
    playbackFrame = null;
  }
}

function handleSliderInput(value) {
  // v-model 已更新 currentTime（watch 负责采样与防抖加载）；此处让播放时钟跟随，便于继续播放。
  const time = clampTime(Number(value) || timeRange.value.min);
  if (isPlaying.value) {
    anchorPlayback(time);
  } else {
    playbackClock = time;
  }
}

function handleSliderCommit(value) {
  cancelSeekChunkLoad();
  const time = clampTime(Number(value) || timeRange.value.min);
  if (isPlaying.value) {
    anchorPlayback(time);
  } else {
    playbackClock = time;
  }
  currentTime.value = time;
  ensureChunkForTime(time);
  seekToTime(time);
}

function formatTime(seconds) {
  const value = Math.max(0, Math.round(Number(seconds) || 0));
  const h = Math.floor(value / 3600).toString().padStart(2, "0");
  const m = Math.floor((value % 3600) / 60).toString().padStart(2, "0");
  const s = (value % 60).toString().padStart(2, "0");
  return `${h}:${m}:${s}`;
}

function formatSchedule(firstTime, lastTime) {
  const first = Number(firstTime);
  const last = Number(lastTime);
  if (!Number.isFinite(first) || !Number.isFinite(last) || (first <= 0 && last <= 0)) {
    return "--";
  }
  return `${formatTime(first)} - ${formatTime(last)}`;
}

function formatLocation(location = {}) {
  if (!location || typeof location !== "object") return "--";
  if (location.label) return location.label;
  if (location.type) return formatPurpose(location.type);
  const x = Number(location.x);
  const y = Number(location.y);
  if (Number.isFinite(x) && Number.isFinite(y)) {
    return `${Math.round(x)}, ${Math.round(y)}`;
  }
  return "--";
}

// MATSim 活动类型（出行目的）→ 中文标签。兼容 home_3600 / work-8h 等带时长后缀写法。
const PURPOSE_LABELS = {
  home: "回家",
  work: "上班",
  business: "公务",
  education: "上学",
  school: "上学",
  university: "上学",
  kindergarten: "上学",
  shopping: "购物",
  shop: "购物",
  errands: "办事",
  leisure: "休闲",
  recreation: "休闲",
  sport: "运动",
  dining: "餐饮",
  eat: "餐饮",
  medical: "就医",
  health: "就医",
  escort: "接送",
  pickup: "接送",
  visiting: "探访",
  social: "社交",
  other: "其他",
};

function formatPurpose(raw) {
  if (raw == null) return "--";
  const text = String(raw).trim();
  if (!text || text === "--") return "--";
  // 去掉时长/编号后缀（home_3600、work-8h、shopping 1 等），并归一化大小写后查表
  const key = text.toLowerCase().replace(/[\s_\-.].*$/, "");
  return PURPOSE_LABELS[key] || text;
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString();
}

function modeLabel(mode) {
  return VEHICLE_MODE_CONFIG[mode]?.label || "车辆";
}

function formatSpeed(value) {
  const speed = Number(value);
  return Number.isFinite(speed) ? `${speed.toFixed(1)} km/h` : "-- km/h";
}

function clearVehicleFollow() {
  trajectoryLayer?.clearFollow();
}

watch(MapRef, ensureTrajectoryLayer, { immediate: true });
watch(VehicleSizeRef, (size) => {
  trajectoryLayer?.setVehicleSize(size);
});
watch(VehicleVisibilityModeRef, (mode) => {
  trajectoryLayer?.setVehicleVisibilityMode(mode);
  syncStats();
});
watch(() => props.model, loadTrajectory, { immediate: true });
watch(currentTime, (time) => {
  // 播放时由 rAF 的 driveLayerTime 直接驱动图层，跳过此 watch，避免每帧重复 setTime 与滑块重渲染。
  if (isPlaying.value) return;
  playbackClock = clampTime(time);
  seekToTime(time);
});

onMounted(() => {
  ensureTrajectoryLayer();
});

onUnmounted(() => {
  stopPlayback();
  stopPolling();
  cancelSeekChunkLoad();
  if (trajectoryLayer) {
    trajectoryLayer.dispose();
    trajectoryLayer = null;
  }
  if (isTrajectoryMonitorActive.value) {
    rightPanelHasContent.value = false;
  }
});
</script>

<style lang="scss" scoped>
.GJYS {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);

  .card {
    .title {
      flex: 1;
      font-weight: bold;
      color: var(--app-ink);
    }

    :deep(.body) {
      display: flex;
      flex-direction: column;
    }
  }

  .loading-state {
    padding: 4px 0;
  }

  .build-state {
    display: flex;
    flex-direction: column;
    gap: var(--space-xs);
    padding: var(--space-xs) 2px 2px;

    .build-title {
      color: var(--app-ink);
      font-size: 13px;
      font-weight: 700;
    }

    .build-metrics {
      display: flex;
      justify-content: space-between;
      color: #64748b;
      font-size: 12px;
    }
  }

  .control-row {
    display: flex;
    align-items: center;
    justify-content: space-between;

    &.flex-col {
      flex-direction: column;
      align-items: stretch;
    }

    .label {
      font-size: 14px;
      color: var(--app-muted);
      font-weight: 500;
    }

    .time-text {
      font-family: "Courier New", Courier, monospace;
      font-weight: bold;
      color: #2f75d6;
      font-size: 16px;
    }

    .slider-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 4px;
    }
  }

  .mt-4 {
    margin-top: 16px;
  }

  .stat-grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: var(--space-sm);
    margin-top: var(--space-xs);

    .stat-item {
      background: rgba(248, 250, 252, 0.8);
      border: 1px solid rgba(226, 232, 240, 0.8);
      padding: var(--space-xs);
      border-radius: var(--app-card-radius);
      display: flex;
      flex-direction: column;

      .stat-label {
        font-size: 11px;
        color: var(--app-muted);
        margin-bottom: 4px;
      }

      .stat-value {
        font-size: 18px;
        font-weight: bold;
        font-family: var(--app-font-number);

        &.text-primary { color: var(--app-blue); }
        &.text-success { color: var(--app-emerald-strong); }
        &.text-warning { color: var(--app-amber); }
        &.mode-bus { color: #16a34a; }
        &.mode-subway { color: #dc4c5d; }
        &.mode-car { color: #2563eb; }

        .unit {
          font-size: 11px;
          font-weight: normal;
          color: var(--app-muted);
          margin-left: 2px;
        }
      }
    }
  }
}

.GJYS_right_card {
  width: 470px;

  .vehicle-panel {
    display: flex;
    flex-direction: column;
    gap: var(--space-sm);
    padding: var(--space-xs) 2px var(--space-2xs);
  }

  .vehicle-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-sm);
    padding: var(--space-xs) 2px var(--space-sm);
    border-bottom: 1px solid rgba(21, 105, 222, 0.12);

    .vehicle-id {
      color: var(--app-ink);
      font-size: 15px;
      font-weight: 800;
      word-break: break-all;
    }

    .vehicle-type {
      margin-top: 2px;
      color: #64748b;
      font-size: 12px;
      font-weight: 600;
    }
  }

  .info-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--space-xs);
  }

  .info-item {
    min-width: 0;
    border: 1px solid rgba(21, 105, 222, 0.12);
    border-radius: var(--app-card-radius);
    background: #f8fbff;
    padding: var(--space-xs) var(--space-sm);
    display: flex;
    flex-direction: column;
    gap: 4px;

    &.wide {
      grid-column: 1 / -1;
    }

    .label {
      color: var(--app-muted);
      font-size: 11px;
      font-weight: 600;
    }

    .value {
      color: var(--app-ink);
      font-size: 13px;
      font-weight: 700;
      line-height: 1.35;
      word-break: break-word;
    }
  }

  .stations-section {
    border-top: 1px solid rgba(21, 105, 222, 0.12);
    padding-top: var(--space-sm);
  }

  .section-title {
    color: var(--app-ink);
    font-size: 13px;
    font-weight: 800;
    margin-bottom: var(--space-xs);
  }

  .station-list {
    max-height: 260px;
    overflow: auto;
    display: flex;
    flex-direction: column;
    gap: var(--space-xs);
    padding-right: 4px;
  }

  .station-row {
    display: grid;
    grid-template-columns: 34px minmax(0, 1fr);
    align-items: center;
    gap: var(--space-xs);
    padding: var(--space-xs);
    border-radius: var(--app-card-radius);
    color: #40506a;
    background: var(--app-card-bg);
    border: 1px solid rgba(226, 232, 240, 0.9);

    &.previous {
      border-color: rgba(22, 163, 74, 0.36);
      background: rgba(22, 163, 74, 0.08);
    }

    &.next {
      border-color: rgba(37, 99, 235, 0.34);
      background: rgba(37, 99, 235, 0.08);
    }

    .station-index {
      color: var(--app-blue);
      font-size: 12px;
      font-weight: 800;
      text-align: right;
    }

    .station-name {
      min-width: 0;
      color: var(--app-ink);
      font-size: 12px;
      font-weight: 650;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  }
}

.run-gjys-control-panel {
  display: flex;
  flex-direction: column;
  gap: 15px;
  margin: 6px 8px 10px;
  padding: 14px 14px 16px;
  border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
  border-radius: var(--dm2-radius, 13px);
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(247, 250, 254, 0.8)),
    var(--dm2-surface, #ffffff);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.85), 0 8px 22px -16px rgba(13, 38, 76, 0.28);
  color: var(--dm2-ink, #1c2024);

  // 标题作为小节标签，呼应左侧导航的层级感
  .run-control-title {
    display: flex;
    align-items: center;
    gap: 7px;
    margin: 0 0 1px;
    color: var(--dm2-accent-strong, #005bb5);
    font-size: 11px;
    font-weight: 760;
    letter-spacing: 0.06em;

    &::before {
      content: "";
      width: 4px;
      height: 13px;
      border-radius: 2px;
      background: var(--dm2-accent-grad, linear-gradient(135deg, #0a84ff, #0071e3));
    }
  }

  // mt-4 的额外外边距交给父级 gap 统一控制，避免双倍间距
  .mt-4 {
    margin-top: 0;
  }

  .build-state {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 10px;
    border-radius: var(--dm2-radius-sm, 10px);
    background: rgba(17, 32, 58, 0.03);
  }

  .build-title {
    color: var(--dm2-ink, #1c2024);
    font-size: 12px;
    font-weight: 700;
  }

  .build-metrics {
    display: flex;
    justify-content: space-between;
    color: var(--dm2-muted, #667085);
    font-size: 11px;
  }

  .control-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;

    &.flex-col {
      align-items: stretch;
      flex-direction: column;
      gap: 9px;
    }
  }

  .label {
    color: var(--dm2-muted, #667085);
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.04em;
  }

  .slider-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .time-text {
    color: var(--dm2-accent-strong, #005bb5);
    font-family: var(--dm2-font-num, "SF Pro Display", monospace);
    font-size: 15px;
    font-weight: 800;
    font-variant-numeric: tabular-nums;
  }

  .btn-group {
    display: flex;
    width: 100%;
  }

  // 播放 / 重置：填充式按钮组，主操作用强调渐变
  :deep(.el-button-group) {
    display: flex;
    width: 100%;
    border: 0;
    border-radius: 10px;
    overflow: hidden;
    box-shadow: var(--dm2-shadow-card, 0 1px 2px rgba(13, 38, 76, 0.05), 0 4px 12px -4px rgba(13, 38, 76, 0.08));
  }

  :deep(.el-button) {
    flex: 1;
    height: 34px;
    padding: 0;
    border: 0;
    border-radius: 0;
    font-size: 12.5px;
    font-weight: 700;
    letter-spacing: 0.02em;
    transition:
      background-color var(--dm2-dur, 240ms) var(--dm2-ease, ease),
      color var(--dm2-dur, 240ms) var(--dm2-ease, ease);
  }

  :deep(.el-button + .el-button) {
    border-left: 1px solid rgba(255, 255, 255, 0.35);
  }

  :deep(.el-button--primary) {
    background: var(--dm2-accent-grad, linear-gradient(135deg, #0a84ff 0%, #0071e3 52%, #0a63cc 100%)) !important;
    color: #ffffff !important;

    &:hover {
      filter: brightness(1.05);
    }
  }

  :deep(.el-button--info) {
    background-color: var(--dm2-field, #f1f4f9) !important;
    color: var(--dm2-ink-soft, #3b4452) !important;

    &:hover {
      background-color: rgba(17, 32, 58, 0.08) !important;
      color: var(--dm2-ink, #1c2024) !important;
    }
  }

  :deep(.el-button.is-disabled) {
    opacity: 0.5;
    cursor: not-allowed;
  }

  // 演示速度：分段控件，选中态清晰高亮（修复旧选择器类名错误导致的"不亮起"）
  :deep(.el-radio-group) {
    display: flex;
    width: 100%;
    gap: 3px;
    padding: 3px;
    border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
    border-radius: 10px;
    background: var(--dm2-field, #f1f4f9);
  }

  :deep(.el-radio-button) {
    flex: 1;
    display: flex;
  }

  :deep(.el-radio-button__inner) {
    width: 100%;
    height: 28px;
    line-height: 28px;
    padding: 0;
    border: 0 !important;
    background: transparent !important;
    color: var(--dm2-ink-soft, #3b4452) !important;
    font-size: 12px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
    border-radius: 7px !important;
    box-shadow: none !important;
    transition:
      background-color var(--dm2-dur-fast, 140ms) var(--dm2-ease, ease),
      color var(--dm2-dur-fast, 140ms) var(--dm2-ease, ease),
      box-shadow var(--dm2-dur-fast, 140ms) var(--dm2-ease, ease);
  }

  :deep(.el-radio-button:not(.is-active):hover .el-radio-button__inner) {
    background: rgba(255, 255, 255, 0.6) !important;
    color: var(--dm2-accent, #0071e3) !important;
  }

  // 同时匹配 is-active（最稳）与正确的原生 input 类名，确保任意 Element Plus 版本都高亮
  :deep(.el-radio-button.is-active .el-radio-button__inner),
  :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner),
  :deep(.el-radio-button__orig-radio:checked + .el-radio-button__inner) {
    background: #ffffff !important;
    color: var(--dm2-accent-strong, #005bb5) !important;
    box-shadow:
      0 1px 4px rgba(13, 38, 76, 0.16),
      inset 0 0 0 1px rgba(0, 113, 227, 0.22) !important;
  }

  :deep(.el-slider) {
    --el-slider-main-bg-color: var(--dm2-accent, #0071e3);
    --el-slider-runway-bg-color: rgba(17, 32, 58, 0.1);
    --el-slider-stop-bg-color: #ffffff;
    --el-slider-button-size: 14px;
    --el-slider-button-wrapper-size: 30px;
    --el-slider-height: 5px;
    height: 26px;
  }

  :deep(.el-slider__button) {
    border: 2px solid var(--dm2-accent, #0071e3);
    box-shadow: 0 1px 4px rgba(13, 38, 76, 0.22);
  }
}

.vehicle-status-right-card {
  /* 主指标卡：实时运行车辆 */
  .rm-veh-hero {
    position: relative;
    overflow: hidden;
    padding: 16px 18px 14px;
    border: 1px solid rgba(0, 113, 227, 0.16);
    border-radius: var(--dm2-radius-lg, 16px);
    background:
      radial-gradient(120% 140% at 100% 0%, rgba(0, 113, 227, 0.12), transparent 60%),
      linear-gradient(180deg, rgba(247, 250, 254, 0.9), rgba(240, 245, 251, 0.78));
    box-shadow:
      0 14px 30px -20px rgba(13, 38, 76, 0.34),
      inset 0 1px 0 rgba(255, 255, 255, 0.7);
  }

  .rm-veh-hero-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .rm-veh-hero-label {
    color: var(--dm2-muted, #667085);
    font-size: 12px;
    font-weight: 650;
    letter-spacing: 0.01em;
  }

  .rm-veh-live {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    color: var(--dm2-accent-strong, #005bb5);
    font-size: 11px;
    font-weight: 600;
  }

  .rm-veh-live-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: #1a8a3f;
    box-shadow: 0 0 0 0 rgba(26, 138, 63, 0.5);
    animation: rmVehPulse 1.8s var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1)) infinite;
  }

  .rm-veh-hero-value {
    margin-top: 2px;
    font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
    font-size: 38px;
    font-weight: 800;
    line-height: 1.05;
    letter-spacing: -0.02em;
    color: var(--dm2-ink, #1c2024);
  }

  .rm-veh-hero-unit {
    margin-left: 4px;
    font-size: 14px;
    font-weight: 600;
    color: var(--dm2-muted, #667085);
  }

  .rm-veh-modes {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
    margin-top: 14px;
  }

  .rm-veh-mode {
    display: flex;
    flex-direction: column;
    gap: 3px;
    padding: 8px 10px;
    border-radius: var(--dm2-radius-sm, 10px);
    background: rgba(255, 255, 255, 0.66);
    border: 1px solid rgba(17, 32, 58, 0.06);
  }

  .rm-veh-mode-name {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--dm2-muted, #667085);
    font-size: 11px;
    font-weight: 600;
  }

  .rm-veh-mode-name::before {
    content: "";
    width: 7px;
    height: 7px;
    border-radius: 2px;
    background: currentColor;
  }

  .rm-veh-mode-val {
    font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
    font-size: 19px;
    font-weight: 800;
    color: var(--dm2-ink, #1c2024);
  }

  .rm-veh-mode.bus .rm-veh-mode-name { color: #1a8a3f; }
  .rm-veh-mode.subway .rm-veh-mode-name { color: #c4291c; }
  .rm-veh-mode.car .rm-veh-mode-name { color: var(--dm2-accent, #0071e3); }

  /* 次级指标 */
  .rm-veh-stats {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
    margin-top: 12px;
  }

  .rm-veh-stat {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 12px 14px;
    border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
    border-radius: var(--dm2-radius, 13px);
    background: rgba(244, 247, 251, 0.7);
  }

  .rm-veh-stat-label {
    color: var(--dm2-muted, #667085);
    font-size: 11px;
    font-weight: 650;
  }

  .rm-veh-stat-value {
    font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
    font-size: 22px;
    font-weight: 800;
    color: var(--dm2-ink, #1c2024);

    &.success { color: #1a8a3f; }
    &.warning { color: #b06a00; }

    .unit {
      margin-left: 3px;
      color: var(--dm2-muted, #667085);
      font-size: 11px;
      font-weight: 500;
    }
  }
}

@keyframes rmVehPulse {
  0% { box-shadow: 0 0 0 0 rgba(26, 138, 63, 0.45); }
  70% { box-shadow: 0 0 0 6px rgba(26, 138, 63, 0); }
  100% { box-shadow: 0 0 0 0 rgba(26, 138, 63, 0); }
}

@media (prefers-reduced-motion: reduce) {
  .vehicle-status-right-card .rm-veh-live-dot {
    animation: none;
  }
}

</style>
