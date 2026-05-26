<!-- 轨迹演示 -->
<template>
  <div class="GJYS" v-bind="$attrs">
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

const props = defineProps({
  model: String,
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
let lastPlaybackFrameAt = 0;
let pollTimer = null;
let loadSeq = 0;
let chunkSeq = 0;
let currentChunkStart = null;
let pendingChunkStart = null;
let chunkCache = new Map();
let prefetchingChunks = new Set();
let passengerTimeIndex = emptyPassengerIndex();
let passengerSeriesRows = [];

const summary = computed(() => trajectoryData.value?.summary || {});
const hasTrajectory = computed(() => cacheStatus.value === "ready" && Number(summary.value.totalVehicles || 0) > 0);
watch([followedVehicle, activeDatavisualizationTab], () => {
  if (activeDatavisualizationTab.value === "轨迹演示") {
    rightPanelHasContent.value = !!followedVehicle.value;
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
  if (!vehicle || activeDatavisualizationTab.value !== "轨迹演示") return null;
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
    purpose: meta.purpose || "--",
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
    liveStats.value = stats || emptyStats();
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
  stopPlayback();
  stopPolling();
  trajectoryData.value = null;
  currentChunkData.value = null;
  currentChunkStart = null;
  pendingChunkStart = null;
  chunkCache = new Map();
  prefetchingChunks = new Set();
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

  const seq = ++loadSeq;
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
    const data = await requestTrajectoryChunk(start);
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
  prefetchAroundTime(currentTime.value);
}

function rememberChunk(start, data) {
  if (!data || data.status !== "ready") return;
  chunkCache.set(start, data);
  trimChunkCache(start);
}

function trimChunkCache(anchorStart) {
  if (chunkCache.size <= MAX_CHUNK_CACHE) return;
  const entries = [...chunkCache.keys()].sort((a, b) => Math.abs(a - anchorStart) - Math.abs(b - anchorStart));
  const keep = new Set(entries.slice(0, MAX_CHUNK_CACHE));
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

async function prefetchChunk(start, seq) {
  if (!isChunkStartInRange(start) || chunkCache.has(start) || prefetchingChunks.has(start) || pendingChunkStart === start) {
    return;
  }
  prefetchingChunks.add(start);
  try {
    const data = await requestTrajectoryChunk(start);
    if (seq === loadSeq && data?.status === "ready") {
      rememberChunk(start, data);
    }
  } catch (error) {
    // Prefetch is opportunistic; the foreground loader will surface errors.
  } finally {
    prefetchingChunks.delete(start);
  }
}

async function requestTrajectoryChunk(start) {
  try {
    const res = await dataTrajectoryChunkBinary({ datasource: props.model }, start);
    if (res?.status === 200) {
      return parseVehicleTrajectoryBinaryChunk(res.data, trajectoryData.value || {});
    }
  } catch (error) {
    // JSON keeps the feature usable when an old cache or browser rejects the binary path.
  }

  const res = await dataTrajectoryChunk({ datasource: props.model }, start);
  return res.data || {};
}

function syncStats() {
  const layerStats = trajectoryLayer?.setTime(currentTime.value) || emptyStats();
  liveStats.value = layerStats;
  const passengerCounts = cumulativeAt(currentTime.value);
  cumulativePassengers.value = passengerCounts.total || 0;
  cumulativeByMode.value = {
    bus: passengerCounts.bus || 0,
    subway: passengerCounts.subway || 0,
    car: passengerCounts.car || 0,
  };
}

function setPlaybackTime(value) {
  const rawTime = Number(value) || timeRange.value.min;
  currentTime.value = rawTime > timeRange.value.max ? timeRange.value.min : clampTime(rawTime);
}

function togglePlay() {
  if (!canControl.value) return;
  isPlaying.value = !isPlaying.value;
  if (isPlaying.value) {
    startPlayback();
  } else {
    stopPlayback();
  }
}

function resetPlayback() {
  currentTime.value = initialTime();
  isPlaying.value = false;
  stopPlayback();
  ensureChunkForTime(currentTime.value);
  syncStats();
}

function changeSpeed() {
  if (isPlaying.value) {
    stopPlayback();
    startPlayback();
  }
}

function startPlayback() {
  stopPlayback();
  lastPlaybackFrameAt = performance.now();
  const tick = (now) => {
    if (!isPlaying.value) return;
    const deltaSeconds = Math.min(0.12, Math.max(0, (now - lastPlaybackFrameAt) / 1000));
    lastPlaybackFrameAt = now;
    setPlaybackTime(currentTime.value + deltaSeconds * playSpeed.value);
    prefetchAroundTime(currentTime.value);
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
  currentTime.value = clampTime(value);
  syncStats();
}

function handleSliderCommit(value) {
  currentTime.value = clampTime(value);
  ensureChunkForTime(currentTime.value);
  syncStats();
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
  if (location.type) return location.type;
  const x = Number(location.x);
  const y = Number(location.y);
  if (Number.isFinite(x) && Number.isFinite(y)) {
    return `${Math.round(x)}, ${Math.round(y)}`;
  }
  return "--";
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
  let appliedCachedChunk = false;
  if (chunkStartOf(time) !== currentChunkStart) {
    const start = chunkStartOf(time);
    if (chunkCache.has(start)) {
      applyChunkData(start, chunkCache.get(start));
      appliedCachedChunk = true;
    } else {
      ensureChunkForTime(time);
    }
  } else {
    prefetchAroundTime(time);
  }
  if (!appliedCachedChunk) {
    syncStats();
  }
});

onMounted(() => {
  ensureTrajectoryLayer();
});

onUnmounted(() => {
  stopPlayback();
  stopPolling();
  if (trajectoryLayer) {
    trajectoryLayer.dispose();
    trajectoryLayer = null;
  }
  if (activeDatavisualizationTab.value === "轨迹演示") {
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
      color: #1a365d;
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
      color: #1a365d;
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
      color: #4a5568;
      font-weight: 500;
    }

    .time-text {
      font-family: "Courier New", Courier, monospace;
      font-weight: bold;
      color: #3b82f6;
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
        color: #718096;
        margin-bottom: 4px;
      }

      .stat-value {
        font-size: 18px;
        font-weight: bold;
        font-family: "Outfit", "Inter", sans-serif;

        &.text-primary { color: #3b82f6; }
        &.text-success { color: #10b981; }
        &.text-warning { color: #f59e0b; }
        &.mode-bus { color: #16a34a; }
        &.mode-subway { color: #dc2626; }
        &.mode-car { color: #2563eb; }

        .unit {
          font-size: 11px;
          font-weight: normal;
          color: #718096;
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
      color: #1a365d;
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
      color: #718096;
      font-size: 11px;
      font-weight: 600;
    }

    .value {
      color: #1a365d;
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
    color: #1a365d;
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
    background: #ffffff;
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
      color: #1569de;
      font-size: 12px;
      font-weight: 800;
      text-align: right;
    }

    .station-name {
      min-width: 0;
      color: #1a365d;
      font-size: 12px;
      font-weight: 650;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  }
}

</style>
