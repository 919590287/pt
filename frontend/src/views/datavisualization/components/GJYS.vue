<!-- 轨迹演示 -->
<template>
  <!-- 运行监测模式（runMonitorPanels=true）下该子树完全不渲染（v-if），避免隐藏面板随播放状态每 120ms 持续 patch -->
  <div class="GJYS" v-bind="$attrs" v-if="!runMonitorPanels">
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

  <teleport to="#run-monitor-playback-dock" defer v-if="runMonitorPanels">
    <div class="rm-play-bar" role="group" aria-label="轨迹演示控制">
      <!-- 加载 / 生成 / 失败：与就绪态同一条形，只换正文，不让控制条忽高忽低 -->
      <div v-if="loading && !trajectoryData" class="rm-play-status">
        <span class="rm-play-spinner" aria-hidden="true"></span>
        <span class="rm-play-status-text">轨迹数据加载中…</span>
      </div>

      <div v-else-if="loadError && !trajectoryData" class="rm-play-status is-error" role="alert">
        <span class="rm-play-status-text">{{ loadError }}</span>
      </div>

      <div v-else-if="isGenerating" class="rm-play-status is-build" role="status">
        <span class="rm-play-spinner" aria-hidden="true"></span>
        <span class="rm-play-status-text">{{ cacheMessage }}</span>
        <span class="rm-play-build-track" aria-hidden="true">
          <span class="rm-play-build-fill" :style="{ width: `${buildProgressPercent}%` }"></span>
        </span>
        <span class="rm-play-build-metrics">车辆 {{ formatNumber(progressInfo.vehicleCount) }} · 轨迹点 {{ formatNumber(progressInfo.pointCount) }}</span>
      </div>

      <template v-else>
        <div class="rm-play-transport">
          <button
            type="button"
            class="rm-play-btn"
            :disabled="!canControl"
            :aria-label="isPlaying ? '暂停' : '播放'"
            @click="togglePlay"
          >
            <svg v-if="isPlaying" viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true">
              <rect x="6" y="5" width="4" height="14" rx="1"></rect>
              <rect x="14" y="5" width="4" height="14" rx="1"></rect>
            </svg>
            <svg v-else viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true">
              <path d="M8 5.5v13a1 1 0 0 0 1.53.85l10-6.5a1 1 0 0 0 0-1.7l-10-6.5A1 1 0 0 0 8 5.5Z"></path>
            </svg>
          </button>
          <button
            type="button"
            class="rm-play-reset"
            :disabled="!canControl"
            aria-label="重置到起点"
            title="重置"
            @click="resetPlayback"
          >
            <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M3 12a9 9 0 1 0 3-6.7"></path>
              <path d="M3 4v4h4"></path>
            </svg>
          </button>
        </div>

        <div class="rm-play-speed" role="group" aria-label="演示速度">
          <button
            v-for="speed in PLAY_SPEEDS"
            :key="speed"
            type="button"
            :class="['rm-play-speed-btn', playSpeed === speed ? 'active' : '']"
            :disabled="!canControl"
            :aria-pressed="playSpeed === speed"
            @click="selectSpeed(speed)"
          >{{ speed }}x</button>
        </div>

        <div class="rm-play-scrub">
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

        <span class="rm-play-time" aria-label="当前时刻">{{ formatTime(currentTime) }}</span>
      </template>
    </div>
  </teleport>

  <!-- 车辆运行监测右侧面板：外壳与线路/站点/客流/体检四块面板同构（无卡中卡、无蓝色标题条、无折叠钮）。
       teleport 出去的节点带的是本组件 scope，样式在本文件内自持。 -->
  <teleport to="#datavisualization_index_box2" defer v-if="runMonitorPanels && !vehicleStaticInfo">
    <section class="rm-veh-card rm-veh-status-card">
      <header class="rm-veh-card-title">
        <h2>车辆运行监测</h2>
        <span class="rm-veh-live"><span class="rm-veh-live-dot"></span>实时</span>
      </header>

      <div class="rm-veh-hero">
        <span class="rm-veh-hero-label">在途车辆</span>
        <p class="rm-veh-hero-value">
          <strong>{{ formatVehCount(activeVehicles) }}</strong>
          <em>辆</em>
        </p>
      </div>

      <!-- 在途车辆构成：占比条即图例，色块与地图上车辆点同色（VEHICLE_MODE_CONFIG）。
           私家车常年占大头，用占比条一眼看清公交/地铁的道路占有比例 -->
      <div class="rm-veh-split">
        <div class="rm-veh-split-bar" role="img" :aria-label="vehicleSplitAriaLabel">
          <span
            v-for="mode in vehicleModeBreakdown"
            :key="mode.key"
            class="rm-veh-split-seg"
            :style="{ width: `${mode.percent}%`, background: mode.color }"
          ></span>
        </div>
        <div class="rm-veh-split-legend">
          <div v-for="mode in vehicleModeBreakdown" :key="mode.key" class="rm-veh-split-row">
            <span class="rm-veh-swatch" :style="{ background: mode.color }" aria-hidden="true"></span>
            <span class="rm-veh-split-name">{{ mode.label }}</span>
            <strong class="rm-veh-split-val">{{ formatVehCount(mode.count) }}</strong>
            <span class="rm-veh-split-pct">{{ mode.percentText }}</span>
          </div>
        </div>
      </div>

      <div class="rm-veh-metrics">
        <div class="rm-veh-metric">
          <span class="rm-veh-metric-label">累计乘车人数</span>
          <strong class="rm-veh-metric-value">
            {{ formatVehCount(cumulativePassengers) }}<em>人次</em>
          </strong>
        </div>
        <div class="rm-veh-metric">
          <span class="rm-veh-metric-label">平均车速</span>
          <strong class="rm-veh-metric-value">
            {{ avgSpeed }}<em>km/h</em>
          </strong>
        </div>
      </div>
    </section>

    <!-- 主要拥堵路段 TOP10：随播放时刻按 15min 桶刷新，点击行定位到地图并高亮该路段。
         数据独立于"路段公交车速"图层开关加载（共享同一份矩阵缓存，图层开启时零额外请求） -->
    <section class="rm-veh-card rm-congest-card">
      <header class="rm-veh-card-title">
        <h2>主要拥堵路段<em class="rm-congest-top-badge">TOP10</em></h2>
        <span v-if="congestStatus === 'ready'" class="rm-congest-window" aria-label="统计时段">{{ congestWindowText }}</span>
      </header>
      <p class="rm-congest-note">{{ congestNote }}</p>

      <div v-if="congestStatus === 'loading'" class="rm-congest-state" role="status">
        <span class="rm-play-spinner" aria-hidden="true"></span>车速数据加载中…
      </div>
      <div v-else-if="congestStatus === 'generating'" class="rm-congest-state" role="status">
        <span class="rm-play-spinner" aria-hidden="true"></span>车速缓存生成中，就绪后自动显示…
      </div>
      <div v-else-if="congestStatus === 'empty'" class="rm-congest-state">该模型无公交车速数据</div>
      <div v-else-if="!congestTop.length" class="rm-congest-state">当前时段无明显拥堵路段</div>

      <ol v-else class="rm-congest-list">
        <li v-for="item in congestTop" :key="item.key">
          <button
            type="button"
            :class="['rm-congest-row', activeCongestKey === item.key ? 'active' : '']"
            :aria-pressed="activeCongestKey === item.key"
            :title="`${item.bandLabel} · 较自由流降速${item.dropText} · 点击定位到地图`"
            @click="focusCongestGroup(item)"
          >
            <span :class="['rm-congest-rank', item.rank <= 3 ? 'is-top' : '']">{{ item.rank }}</span>
            <span class="rm-congest-main">
              <span class="rm-congest-name">{{ item.name }}</span>
              <span class="rm-congest-sub">{{ item.sub || "—" }}</span>
            </span>
            <span class="rm-congest-speed">
              <span class="rm-congest-speed-val">
                <i class="rm-congest-dot" :style="{ background: item.bandColor }" aria-hidden="true"></i>
                <strong>{{ item.speedKmh }}</strong><em>km/h</em>
              </span>
              <span class="rm-congest-drop">延误 {{ item.delayText }}</span>
            </span>
          </button>
        </li>
      </ol>
    </section>
  </teleport>

  <!-- 跟随某车时替换上面的状态卡，用同一套扁平外壳，避免"跟随/取消跟随"在两种卡片外壳间跳变 -->
  <teleport to="#datavisualization_index_box2" defer v-if="vehicleStaticInfo">
    <section class="rm-veh-card rm-veh-info-card">
      <header class="rm-veh-card-title">
        <div class="rm-veh-info-head">
          <h2>{{ vehicleStaticInfo.id }}</h2>
          <p class="rm-veh-info-type">{{ vehicleStaticInfo.typeLabel }}</p>
        </div>
        <button type="button" class="rm-veh-unfollow" @click="clearVehicleFollow">解除跟随</button>
      </header>

      <div class="rm-veh-info-body">
          <div class="info-grid">
            <div class="info-item">
              <span class="label">当前速度</span>
              <span class="value">{{ vehicleDynamicInfo.speed }}</span>
            </div>
            <template v-if="vehicleStaticInfo.isTransit">
              <div class="info-item">
                <span class="label">线路名称</span>
                <span class="value">{{ vehicleStaticInfo.lineName }}</span>
              </div>
              <div class="info-item">
                <span class="label">上一站</span>
                <span class="value">{{ vehicleDynamicInfo.previousStation }}</span>
              </div>
              <div class="info-item">
                <span class="label">下一站</span>
                <span class="value">{{ vehicleDynamicInfo.nextStation }}</span>
              </div>
              <div class="info-item">
                <span class="label">车内人员</span>
                <span class="value">{{ vehicleDynamicInfo.passengerCount }}</span>
              </div>
              <div class="info-item">
                <span class="label">满载率</span>
                <span class="value">{{ vehicleDynamicInfo.loadRate }}</span>
              </div>
              <div class="info-item wide">
                <span class="label">运营时段</span>
                <span class="value">{{ vehicleStaticInfo.schedule }}</span>
              </div>
            </template>
            <template v-else>
              <div class="info-item">
                <span class="label">车内人员</span>
                <span class="value">{{ vehicleStaticInfo.personCount }}</span>
              </div>
              <div class="info-item wide">
                <span class="label">出发地</span>
                <span class="value">{{ vehicleStaticInfo.origin }}</span>
              </div>
              <div class="info-item wide">
                <span class="label">目的地</span>
                <span class="value">{{ vehicleStaticInfo.destination }}</span>
              </div>
              <div class="info-item wide">
                <span class="label">出行目的</span>
                <span class="value">{{ vehicleStaticInfo.purpose }}</span>
              </div>
            </template>
          </div>

          <div class="stations-section" v-if="vehicleStaticInfo.isTransit">
            <div class="section-title">完整站序</div>
            <!-- 站点列表遍历原始 stops 数组（不克隆）；上一站/下一站高亮由动态下标比较驱动 -->
            <div class="station-list">
              <div
                v-for="(station, index) in vehicleStaticInfo.stops"
                :key="station.id || station.index || index + 1"
                :class="['station-row', index === vehicleDynamicInfo.previousIndex ? 'previous' : index === vehicleDynamicInfo.nextIndex ? 'next' : '']"
              >
                <span class="station-index">{{ station.index || index + 1 }}</span>
                <span class="station-name">{{ station.name }}</span>
              </div>
            </div>
          </div>
      </div>
    </section>
  </teleport>
</template>

<script setup>
import { computed, inject, markRaw, onMounted, onUnmounted, ref, shallowRef, watch } from "vue";
import MCard from "./MCard.vue";
import {
  dataTrajectory,
  dataTrajectoryChunk,
  dataTrajectoryChunkBinary,
  dataTrajectoryFrameBinary,
} from "@/api/trajectory.js";
import { VehicleTrajectoryLayer, VEHICLE_MODE_CONFIG, parseVehicleTrajectoryBinaryChunk } from "../layers/VehicleTrajectoryLayer.js";
import { LinkSpeedHighlightManager, LinkSpeedLayerManager } from "../layers/LinkSpeedLayer.js";
import { getCachedChunk, putCachedChunk, pruneChunkCache } from "@/utils/trajectoryChunkCache.js";
import { getCachedLinkSpeedMatrix, getCachedLinkSpeedSummary, getModelDerived } from "@/utils/modelDataCache.js";
import {
  CONGEST_MIN_SPEED_KMH,
  CONGEST_SPEED_RATIO_MAX,
  LINK_SPEED_U16_SENTINEL,
  buildLinkSpeedFreeflow,
  linkSpeedBucketOf,
  parseLinkSpeedMatrix,
  selectCongestedGroups,
} from "../utils/linkSpeed.js";
import { isCanceledRequest } from "../utils/panelShared.js";
import { MAP_THEME } from "@/utils/mapTheme.js";
import { classifyByBreaks } from "@/utils/colorSchemes.js";
import { mercatorToLngLat } from "../utils/populationGrid.js";

const props = defineProps({
  model: String,
  runMonitorPanels: {
    type: Boolean,
    default: false,
  },
});

const MODE_KEYS = ["bus", "subway", "car"];
const PLAY_SPEEDS = [1, 5, 10, 50];

const MapRef = inject("MapRef");
const addPageMapLayer = inject("AddPageMapLayer", (layer) => MapRef?.value?.addLayer(layer));
const rightPanelHasContent = inject("rightPanelHasContent", ref(false));
const activeDatavisualizationTab = inject("activeDatavisualizationTab", ref(""));
const VehicleSizeRef = inject("VehicleSizeRef", ref(36));
const VehicleVisibilityModeRef = inject("VehicleVisibilityModeRef", ref("all"));
// 路段公交车速（拥堵路况）图层：开关/透明度由 index.vue 设置面板下发，状态回报给左下角图例
const LinkSpeedEnabledRef = inject("LinkSpeedEnabledRef", ref(false));
const LinkSpeedOpacityRef = inject("LinkSpeedOpacityRef", ref(85));
const LinkSpeedStatusRef = inject("LinkSpeedStatusRef", ref("idle"));
const DEFAULT_CHUNK_SECONDS = 300;
const PREFETCH_WINDOW_SECONDS = 120;
const MAX_CHUNK_CACHE = 7;
// 分块缓存按字节预算淘汰，避免大 events 下 7 个大分块常驻导致 OOM。
const MAX_CHUNK_CACHE_BYTES = 96 * 1024 * 1024;
const MAX_BACKGROUND_PREFETCH_BYTES = 32 * 1024 * 1024;
const MAX_PERSISTENT_CACHE_BYTES = 64 * 1024 * 1024;
// 播放时滑块/时间文本的刷新节流（图层仍按 rAF 每帧驱动，UI 不必每帧重渲染）。
const UI_SYNC_INTERVAL_MS = 120;
const SEEK_CHUNK_LOAD_DELAY_MS = 48;
const SEEK_SNAPSHOT_DELAY_MS = 36;
const loading = ref(false);
const loadError = ref("");
const cacheStatus = ref("idle");
const cacheMessage = ref("");
const progressInfo = ref({});
const isPlaying = ref(false);
const playSpeed = ref(10);
const currentTime = ref(28800);
const timeRange = ref({ min: 0, max: 86400 });
// 轨迹清单/分块/跟随车辆均为接口返回的大对象（未经 modelDataCache），用 shallowRef+markRaw 保持裸对象：
// 只需整体替换触发响应，深层字段无需代理；传给图层/Worker 时 postMessage 结构化克隆不再走 Proxy 陷阱。
const trajectoryData = shallowRef(null);
const currentChunkData = shallowRef(null);
const cumulativePassengers = ref(0);
const liveStats = ref(emptyStats());
const followedVehicle = shallowRef(null);

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
let seekRenderFrame = null;
let pendingSeekRenderTime = null;
// 拖动进度条到未缓存分块时的防抖加载，避免每次 input 都发请求/清空车辆造成闪烁与长时间空白。
let seekChunkTimer = null;
let seekSnapshotTimer = null;
let seekSnapshotController = null;
let seekSnapshotSeq = 0;
let activeSnapshotRange = null;
let prefetchTimer = null;
let pollTimer = null;
let loadSeq = 0;
let chunkSeq = 0;
let currentChunkStart = null;
let pendingChunkStart = null;
let chunkCache = new Map();
let prefetchingChunks = new Set();
let chunkRequests = new Map();
// 当前一代分块请求共用的取消控制器：重新加载/模型切换/卸载时统一 abort 在途请求（含预取）。
let chunkAbortController = null;
let foregroundChunkController = null;
let prefetchAbortController = null;
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

// 千分位，四位数以上的车辆数/人次一眼看出量级
function formatVehCount(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.round(number).toLocaleString("zh-CN") : "0";
}

// 在途车辆按制式拆分（占比条 + 图例）。颜色取自 VEHICLE_MODE_CONFIG，
// 与地图上车辆点同源；占比按总量归一，0 车时不画色块也不除零
const vehicleModeBreakdown = computed(() => {
  const counts = activeByMode.value;
  const total = MODE_KEYS.reduce((sum, key) => sum + (Number(counts[key]) || 0), 0);
  return MODE_KEYS.map((key) => {
    const count = Number(counts[key]) || 0;
    const share = total > 0 ? (count / total) * 100 : 0;
    return {
      key,
      label: VEHICLE_MODE_CONFIG[key]?.label || key,
      color: VEHICLE_MODE_CONFIG[key]?.color || "#94a3b8",
      count,
      percent: share,
      percentText: total > 0 ? `${Math.round(share)}%` : "--",
    };
  });
});

const vehicleSplitAriaLabel = computed(() =>
  vehicleModeBreakdown.value.map((mode) => `${mode.label} ${mode.count} 辆 ${mode.percentText}`).join("，")
);
const segmentBucketSeconds = computed(() => {
  const speed = Number(playSpeed.value) || 1;
  if (speed >= 80) return 4;
  if (speed >= 30) return 2;
  return 1;
});
// 跟随面板信息拆分为"静态/动态"两个 computed：
// - 静态：车辆身份/线路/原始 stops 数组引用（不 map 不克隆），换车才有实际变化；
// - 动态：随播放时间与车辆位置变化的轻量标量（速度/载客/上下站下标）。
// 原先单个 computed 每次 currentTime 节流更新（120ms）都克隆整条线路全部站点，8Hz 全量重算浪费明显。
const vehicleStaticInfo = computed(() => {
  const vehicle = followedVehicle.value;
  if (!vehicle || !isTrajectoryMonitorActive.value) return null;
  const meta = vehicle.meta || {};
  const mode = normalizeMode(meta.mode || vehicle.mode);
  const route = vehicle.route || {};
  const isTransit = mode === "bus" || mode === "subway";
  const personCount = Number(meta.personCount) || (Array.isArray(meta.personIds) ? meta.personIds.length : 0) || 1;

  return {
    id: meta.id || vehicle.id || vehicle.key || "--",
    typeLabel: modeLabel(mode),
    isTransit,
    lineName: meta.lineName || route.lineName || meta.lineId || "--",
    schedule: formatSchedule(meta.firstTime ?? route.firstTime, meta.lastTime ?? route.lastTime),
    // 原始站点数组引用：模板直接 v-for，行高亮改由 vehicleDynamicInfo 的下标比较驱动
    stops: Array.isArray(route.stops) ? route.stops : [],
    personCount: `${personCount} 人`,
    origin: formatLocation(meta.origin),
    destination: formatLocation(meta.destination),
    purpose: formatPurpose(meta.purpose ?? meta.destination?.type),
  };
});
const vehicleDynamicInfo = computed(() => {
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

  return {
    speed: formatSpeed(vehicle.speed),
    previousStation: stationState.previous?.name || "--",
    nextStation: stationState.next?.name || "--",
    previousIndex: stationState.previousIndex,
    nextIndex: stationState.nextIndex,
    passengerCount: `${passengerCount} 人`,
    loadRate,
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
  trajectoryLayer.setSegmentBucketSeconds(segmentBucketSeconds.value);
  trajectoryLayer.setStatsCallback((stats) => {
    publishLiveStats(stats);
  });
  trajectoryLayer.setFollowCallback((vehicle) => {
    // 图层回调传入的车辆对象每次都是新引用（enrichVehicle 展开构建），
    // markRaw 保持裸对象，面板 computed 读取时不产生深层代理开销。
    followedVehicle.value = vehicle ? markRaw(vehicle) : null;
  });
  addPageMapLayer(trajectoryLayer);
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
  cancelSeekSnapshot();
  cancelScheduledPrefetch();
  cancelSeekRender();
  cancelPrefetchRequests();
  trajectoryData.value = null;
  currentChunkData.value = null;
  currentChunkStart = null;
  activeSnapshotRange = null;
  pendingChunkStart = null;
  chunkCache = new Map();
  prefetchingChunks = new Set();
  chunkRequests = new Map();
  // 模型切换/重新加载：中止上一代仍在途的分块请求（含预取），避免过期响应占用带宽与解析开销。
  chunkAbortController?.abort();
  chunkAbortController = typeof AbortController !== "undefined" ? new AbortController() : null;
  foregroundChunkController?.abort();
  foregroundChunkController = null;
  cacheStatus.value = "idle";
  cacheMessage.value = "";
  progressInfo.value = {};
  liveStats.value = emptyStats();
  followedVehicle.value = null;
  cumulativePassengers.value = 0;
  passengerTimeIndex = emptyPassengerIndex();
  passengerSeriesRows = [];
  if (trajectoryLayer) {
    trajectoryLayer.setData(null);
  }
  if (!props.model) return;

  loading.value = true;
  loadError.value = "";
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
  // 接口清单整体替换（shallowRef 即触发依赖）；markRaw 保证后续透传图层/Worker 的是裸对象。
  trajectoryData.value = markRaw(data);
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

async function loadChunkForTime(time, seq, force = false, options = {}) {
  const { priority = false } = options;
  const start = chunkStartOf(time);
  if (!force && currentChunkStart === start && currentChunkData.value) return;
  if (!force && chunkCache.has(start)) {
    applyChunkData(start, chunkCache.get(start));
    return;
  }
  if (!force && pendingChunkStart === start) return;
  if (priority) {
    cancelPrefetchRequests();
    if (foregroundChunkController && pendingChunkStart !== start) {
      foregroundChunkController.abort();
    }
  }
  const myChunkSeq = ++chunkSeq;
  pendingChunkStart = start;
  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  foregroundChunkController = controller;
  try {
    const data = await requestTrajectoryChunkOnce(start, { signal: controller?.signal });
    if (seq !== loadSeq || myChunkSeq !== chunkSeq) return;
    if (data.status !== "ready") {
      await applyTrajectoryStatus(data, seq);
      return;
    }
    rememberChunk(start, data);
    applyChunkData(start, data);
  } catch (error) {
    // 分块加载失败或被取消（模型切换/卸载时 abort）：静默即可，
    // 播放/拖动路径跨块时会自动重试，界面由既有加载态兜底。
  } finally {
    if (pendingChunkStart === start) {
      pendingChunkStart = null;
    }
    if (foregroundChunkController === controller) {
      foregroundChunkController = null;
    }
  }
}

function applyChunkData(start, data) {
  // 完整分块已就绪，立即接管此前的视口快照并取消同一跳转的在途快照请求。
  cancelSeekSnapshot();
  activeSnapshotRange = null;
  currentChunkStart = start;
  // markRaw：分块对象会原样透传图层→Worker（postMessage），保持裸对象避免结构化克隆走代理。
  currentChunkData.value = data ? markRaw(data) : null;
  trajectoryLayer?.setVehicleMeta(data?.meta || trajectoryData.value?.meta || {});
  trajectoryLayer?.setData(data);
  syncStats();
  scheduleChunkPrefetch(start, currentTime.value);
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

function currentChunkBytes() {
  return chunkBytes(currentChunkData.value);
}

function canBackgroundPrefetch() {
  if (!hasTrajectory.value || !props.model) return false;
  const bytes = currentChunkBytes();
  return bytes > 0 && bytes <= MAX_BACKGROUND_PREFETCH_BYTES;
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
  if (!canBackgroundPrefetch()) return;
  const start = chunkStartOf(time);
  const offset = Math.max(0, Number(time) - start);
  if (offset >= chunkSeconds.value - PREFETCH_WINDOW_SECONDS) {
    prefetchChunk(start + chunkSeconds.value, loadSeq);
  }
  if (!isPlaying.value && offset <= PREFETCH_WINDOW_SECONDS) {
    prefetchChunk(start - chunkSeconds.value, loadSeq);
  }
}

function prefetchAdjacentChunks(start) {
  if (!canBackgroundPrefetch()) return;
  prefetchChunk(start + chunkSeconds.value, loadSeq);
  prefetchChunk(start - chunkSeconds.value, loadSeq);
}

function scheduleChunkPrefetch(start, time = currentTime.value) {
  cancelScheduledPrefetch();
  const delay = isPlaying.value ? 80 : 650;
  prefetchTimer = window.setTimeout(() => {
    prefetchTimer = null;
    if (!canBackgroundPrefetch()) return;
    prefetchAroundTime(time);
    if (!isPlaying.value) {
      prefetchAdjacentChunks(start);
    }
  }, delay);
}

function cancelScheduledPrefetch() {
  if (prefetchTimer) {
    window.clearTimeout(prefetchTimer);
    prefetchTimer = null;
  }
}

function cancelPrefetchRequests() {
  cancelScheduledPrefetch();
  prefetchAbortController?.abort();
  prefetchAbortController = null;
  prefetchingChunks = new Set();
  for (const [key, promise] of chunkRequests) {
    if (String(key).startsWith("bg:")) {
      chunkRequests.delete(key);
    }
  }
}

async function prefetchChunk(start, seq) {
  if (!isChunkStartInRange(start) || chunkCache.has(start) || prefetchingChunks.has(start) || pendingChunkStart === start) {
    return;
  }
  if (!canBackgroundPrefetch()) return;
  prefetchingChunks.add(start);
  if (!prefetchAbortController || prefetchAbortController.signal?.aborted) {
    prefetchAbortController = typeof AbortController !== "undefined" ? new AbortController() : null;
  }
  try {
    const data = await requestTrajectoryChunkOnce(start, {
      background: true,
      signal: prefetchAbortController?.signal,
    });
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

async function requestTrajectoryChunkOnce(start, options = {}) {
  const key = `${options.background ? "bg" : "fg"}:${start}`;
  if (chunkRequests.has(key)) {
    return chunkRequests.get(key);
  }
  const requestMap = chunkRequests;
  const promise = requestTrajectoryChunk(start, options)
    .finally(() => {
      if (requestMap.get(key) === promise) {
        requestMap.delete(key);
      }
    });
  requestMap.set(key, promise);
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

async function requestTrajectoryChunk(start, options = {}) {
  const { background = false, signal } = options;
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
  // 网络请求挂到当前一代的取消控制器上；重新加载/卸载时统一 abort（IndexedDB 命中路径不涉网，不受影响）。
  try {
    const res = await dataTrajectoryChunkBinary({ datasource: props.model }, start, {
      signal,
      silentError: background,
    });
    if (res?.status === 200 && res.data?.byteLength) {
      const parsed = parseVehicleTrajectoryBinaryChunk(res.data, trajectoryData.value || {});
      if (cacheKey && res.data.byteLength <= MAX_PERSISTENT_CACHE_BYTES) {
        putCachedChunk(cacheKey, res.data, { ds: props.model, ver: eventsTag() }).catch(() => {});
      }
      return parsed;
    }
  } catch (error) {
    // 被取消的请求直接上抛，不再降级 JSON 重试（调用方按取消静默处理）。
    if (isCanceledRequest(error)) throw error;
    const status = error?.cause?.response?.status || error?.response?.status;
    if (background || status >= 500) throw error;
    // JSON keeps the feature usable when an old cache or browser rejects the binary path.
  }

  const res = await dataTrajectoryChunk({ datasource: props.model }, start, {
    signal,
    silentError: background,
  });
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
      loadChunkForTime(time, loadSeq, false, { priority: true });
      const snapshotReady = activeSnapshotRange
        && time >= activeSnapshotRange.start
        && time < activeSnapshotRange.end;
      if (!snapshotReady) {
        syncPassengerStatsAt(time);
        return;
      }
    }
  } else {
    prefetchAroundTime(time);
  }
  syncStatsAt(time);
}

// 暂停状态下的拖动/跳转：命中缓存即时切换；未缓存则先在当前分块采样（保留车辆），并防抖加载目标分块。
function seekToTime(time, priority = false) {
  cancelPrefetchRequests();
  const start = chunkStartOf(time);
  if (start !== currentChunkStart) {
    if (chunkCache.has(start)) {
      applyChunkData(start, chunkCache.get(start));
      return;
    }
    syncPassengerStatsAt(time);
    scheduleSeekSnapshot(time, priority);
    scheduleSeekChunkLoad(time, priority);
    if (activeSnapshotRange && time >= activeSnapshotRange.start && time < activeSnapshotRange.end) {
      syncStatsAt(time);
    }
    return;
  }
  syncStatsAt(time);
}

function scheduleSeekChunkLoad(time, priority = false) {
  if (seekChunkTimer) window.clearTimeout(seekChunkTimer);
  seekChunkTimer = window.setTimeout(() => {
    seekChunkTimer = null;
    loadChunkForTime(time, loadSeq, false, { priority });
  }, priority ? 0 : SEEK_CHUNK_LOAD_DELAY_MS);
}

function cancelSeekChunkLoad() {
  if (seekChunkTimer) {
    window.clearTimeout(seekChunkTimer);
    seekChunkTimer = null;
  }
}

function cancelSeekSnapshot() {
  seekSnapshotSeq += 1;
  if (seekSnapshotTimer) {
    window.clearTimeout(seekSnapshotTimer);
    seekSnapshotTimer = null;
  }
  seekSnapshotController?.abort();
  seekSnapshotController = null;
}

function scheduleSeekSnapshot(time, priority = false) {
  if (!hasTrajectory.value || !props.model) return;
  const start = chunkStartOf(time);
  if (start === currentChunkStart || chunkCache.has(start)) return;
  cancelSeekSnapshot();
  const seq = seekSnapshotSeq;
  seekSnapshotTimer = window.setTimeout(() => {
    seekSnapshotTimer = null;
    requestSeekSnapshot(time, seq);
  }, priority ? 0 : SEEK_SNAPSHOT_DELAY_MS);
}

async function requestSeekSnapshot(time, seq) {
  if (seq !== seekSnapshotSeq || !props.model) return;
  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  seekSnapshotController = controller;
  const targetStart = chunkStartOf(time);
  try {
    const bounds = trajectoryLayer?.workerSamplingPayload?.() || null;
    const res = await dataTrajectoryFrameBinary(
      { datasource: props.model },
      time,
      {
        bucketSeconds: chunkSeconds.value,
        visibilityMode: VehicleVisibilityModeRef.value,
        bounds,
      },
      { signal: controller?.signal, silentError: true },
    );
    if (
      seq !== seekSnapshotSeq
      || targetStart === currentChunkStart
      || !res?.data?.byteLength
    ) return;
    const snapshot = parseVehicleTrajectoryBinaryChunk(res.data, trajectoryData.value || {});
    snapshot.snapshotKey = `${targetStart}:${seq}`;
    snapshot.snapshot = true;
    activeSnapshotRange = {
      start: Number(snapshot.chunk?.start) || targetStart,
      end: (Number(snapshot.chunk?.end) || targetStart) + 1,
    };
    trajectoryLayer?.setVehicleMeta(snapshot.meta || trajectoryData.value?.meta || {});
    trajectoryLayer?.setData(markRaw(snapshot));
    syncStatsAt(time, true);
  } catch (error) {
    // 快照是完整分块加载前的低延迟路径；取消/失败时由完整分块自然接管。
  } finally {
    if (seekSnapshotController === controller) seekSnapshotController = null;
  }
}

function cancelSeekRender() {
  pendingSeekRenderTime = null;
  if (seekRenderFrame) {
    window.cancelAnimationFrame(seekRenderFrame);
    seekRenderFrame = null;
  }
}

function scheduleSeekRender(time, priority = false) {
  pendingSeekRenderTime = clampTime(time);
  if (seekRenderFrame) return;
  seekRenderFrame = window.requestAnimationFrame(() => {
    seekRenderFrame = null;
    const nextTime = pendingSeekRenderTime;
    pendingSeekRenderTime = null;
    if (nextTime == null) return;
    seekToTime(nextTime, priority);
  });
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
  cancelSeekRender();
  cancelPrefetchRequests();
  const time = initialTime();
  anchorPlayback(time);
  currentTime.value = time;
  loadChunkForTime(time, loadSeq, false, { priority: true });
  syncStatsAt(time);
}

function changeSpeed() {
  trajectoryLayer?.setSegmentBucketSeconds(segmentBucketSeconds.value);
  // 变速时以当前时刻重锚，避免把新倍速错误地应用到已过去的真实时长上（否则会瞬跳）。
  if (isPlaying.value) {
    anchorPlayback(playbackClock);
  }
}

// 分档倍速按钮（取代 el-radio-group）：设值后复用 changeSpeed 的重锚逻辑
function selectSpeed(speed) {
  if (!canControl.value || playSpeed.value === speed) return;
  playSpeed.value = speed;
  changeSpeed();
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
  cancelPrefetchRequests();
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
  cancelPrefetchRequests();
  if (isPlaying.value) {
    anchorPlayback(time);
    seekToTime(time, false);
  } else {
    playbackClock = time;
    scheduleSeekRender(time);
  }
}

function handleSliderCommit(value) {
  cancelSeekChunkLoad();
  cancelSeekRender();
  cancelPrefetchRequests();
  const time = clampTime(Number(value) || timeRange.value.min);
  if (isPlaying.value) {
    anchorPlayback(time);
  } else {
    playbackClock = time;
  }
  currentTime.value = time;
  seekToTime(time, true);
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
watch(playSpeed, () => {
  trajectoryLayer?.setSegmentBucketSeconds(segmentBucketSeconds.value);
});
watch(() => props.model, loadTrajectory, { immediate: true });
watch(currentTime, (time) => {
  // 播放时由 rAF 的 driveLayerTime 直接驱动图层，跳过此 watch，避免每帧重复 setTime 与滑块重渲染。
  if (isPlaying.value) return;
  playbackClock = clampTime(time);
  scheduleSeekRender(time);
});

// ===== 路段公交车速图层（需求：可开关，默认关；随播放时钟按 15min 桶分时着色）=====
// 数据：link-speed-v1 缓存（summary 轮询 generating → matrix.bin 解析共享缓存）；
// 时钟：挂 currentTime 节流 ref（120ms 粒度对 15min 桶绰绰有余，不进 rAF 热路径）。
const LINK_SPEED_POLL_MS = 8000;
let linkSpeedManager = null;
let linkSpeedSeq = 0;
let linkSpeedPollTimer = null;

function stopLinkSpeedPolling() {
  if (linkSpeedPollTimer) {
    clearTimeout(linkSpeedPollTimer);
    linkSpeedPollTimer = null;
  }
}

function scheduleLinkSpeedPoll() {
  stopLinkSpeedPolling();
  linkSpeedPollTimer = setTimeout(() => {
    linkSpeedPollTimer = null;
    bootstrapLinkSpeed();
  }, LINK_SPEED_POLL_MS);
}

function ensureLinkSpeedManager() {
  if (!linkSpeedManager) {
    linkSpeedManager = new LinkSpeedLayerManager();
  }
  if (MapRef?.value) {
    linkSpeedManager.attach(MapRef.value);
  }
  return linkSpeedManager;
}

function syncLinkSpeedBucket(simSeconds) {
  const data = linkSpeedManager?.data;
  if (!data || !data.count) return;
  linkSpeedManager.setBucket(linkSpeedBucketOf(simSeconds, data.buckets, data.bucketSeconds));
}

async function bootstrapLinkSpeed() {
  stopLinkSpeedPolling();
  if (!props.model || !LinkSpeedEnabledRef.value) return;
  const seq = ++linkSpeedSeq;
  const model = props.model;
  if (LinkSpeedStatusRef.value !== "generating") {
    LinkSpeedStatusRef.value = "loading";
  }
  try {
    const summary = await getCachedLinkSpeedSummary(model);
    if (seq !== linkSpeedSeq || props.model !== model || !LinkSpeedEnabledRef.value) return;
    if (!summary || summary.status === "generating") {
      LinkSpeedStatusRef.value = "generating";
      scheduleLinkSpeedPoll();
      return;
    }
    const version = String(summary.generatedAt || summary.cacheVersion || "");
    const buffer = await getCachedLinkSpeedMatrix(model, version);
    if (seq !== linkSpeedSeq || props.model !== model || !LinkSpeedEnabledRef.value) return;
    if (!buffer) {
      LinkSpeedStatusRef.value = "generating";
      scheduleLinkSpeedPoll();
      return;
    }
    const parsed = getModelDerived(model, "linkSpeedMatrix", () => markRaw(parseLinkSpeedMatrix(buffer)));
    const manager = ensureLinkSpeedManager();
    manager.setData(parsed);
    manager.setOpacity(Number(LinkSpeedOpacityRef.value) / 100);
    manager.setVisible(true);
    syncLinkSpeedBucket(isPlaying.value ? playbackClock : currentTime.value);
    LinkSpeedStatusRef.value = parsed.count > 0 ? "ready" : "empty";
  } catch (error) {
    if (seq !== linkSpeedSeq || isCanceledRequest(error)) return;
    // 后端旧版本无该接口/缓存未建：按生成中轮询，部署后自动出图
    LinkSpeedStatusRef.value = "generating";
    scheduleLinkSpeedPoll();
  }
}

watch(LinkSpeedEnabledRef, (enabled) => {
  if (enabled) {
    if (linkSpeedManager?.data?.count) {
      linkSpeedManager.setVisible(true);
      syncLinkSpeedBucket(isPlaying.value ? playbackClock : currentTime.value);
      LinkSpeedStatusRef.value = "ready";
    } else {
      bootstrapLinkSpeed();
    }
  } else {
    linkSpeedSeq += 1;
    stopLinkSpeedPolling();
    linkSpeedManager?.setVisible(false);
    LinkSpeedStatusRef.value = "idle";
  }
});

watch(LinkSpeedOpacityRef, (value) => {
  linkSpeedManager?.setOpacity(Number(value) / 100);
});

// ===== 主要拥堵路段 TOP10（右侧面板卡片，随播放时钟按 15min 桶刷新，点击定位）=====
// 数据与车速图层共享同一份 summary/matrix 缓存（modelDataCache 去重），但状态机独立：
// 面板信息不被"默认关闭"的图层开关卡住，图层后开时命中缓存秒出。
const CONGEST_TOP_LIMIT = 10;
const congestStatus = ref("loading"); // loading | generating | ready | empty
const congestSummary = shallowRef(null); // { names: 路名字典, districts: 街道→行政区 }
const congestData = shallowRef(null); // parseLinkSpeedMatrix 结果（与图层共享 markRaw 对象）
const congestFreeflow = shallowRef(null); // 每链路自由流基准（全天最大桶速，模型级派生缓存）
const congestBucket = ref(linkSpeedBucketOf(28800, 96, 900)); // 独立桶 ref：currentTime 每 120ms 变，跨桶才触发榜单重算
const activeCongestKey = ref("");
let congestSeq = 0;
let congestPollTimer = null;
let congestHighlightManager = null;

// 口径一句话（常量单一来源）：准入=降速幅度，排序=累计延误，钳位爬行值按异常剔除
const congestNote = `公交净行驶车速较自由流降速≥${Math.round((1 - CONGEST_SPEED_RATIO_MAX) * 100)}%的路段，`
  + `按时段累计延误排序、随播放时刻变化（模型抽样口径，低于 ${CONGEST_MIN_SPEED_KMH} km/h 的异常穿越已剔除）`;

const congestWindowText = computed(() => {
  const bucketSeconds = congestData.value?.bucketSeconds || 900;
  const start = congestBucket.value * bucketSeconds;
  return `${formatClock(start)}–${formatClock(start + bucketSeconds)}`;
});

const congestTop = computed(() => {
  const data = congestData.value;
  const freeflow = congestFreeflow.value;
  if (congestStatus.value !== "ready" || !data || !freeflow) return [];
  const summary = congestSummary.value || {};
  const names = Array.isArray(summary.names) ? summary.names : [];
  const districts = Array.isArray(summary.districts) ? summary.districts : [];
  const theme = MAP_THEME.linkSpeed;
  return selectCongestedGroups(data, freeflow, congestBucket.value, CONGEST_TOP_LIMIT).map((group, index) => {
    const band = classifyByBreaks(group.speedKmh, theme.breaks);
    const roadName = group.nameIdx >= 0 ? names[group.nameIdx] || "" : "";
    const district = group.street !== LINK_SPEED_U16_SENTINEL ? districts[group.street] || "" : "";
    const segNote = group.links.length > 1 ? `${group.links.length} 段拥堵` : "";
    // 有路名：路名为主、行政区+段数为辅；无路名：行政区提为主标题，避免十行全是"未命名路段"
    let name;
    let sub;
    if (roadName) {
      name = roadName;
      sub = [district, segNote].filter(Boolean).join(" · ");
    } else if (district) {
      name = district;
      sub = ["未命名路段", segNote].filter(Boolean).join(" · ");
    } else {
      name = "未命名路段";
      sub = segNote;
    }
    return {
      ...group,
      rank: index + 1,
      name,
      sub,
      dropText: `${Math.round((1 - group.speedKmh / group.freeflowKmh) * 100)}%`,
      delayText: formatDelay(group.delaySeconds),
      bandColor: theme.colors[band],
      bandLabel: theme.labels[band],
    };
  });
});

function formatClock(seconds) {
  const total = ((Math.round(seconds) % 86400) + 86400) % 86400;
  const h = String(Math.floor(total / 3600)).padStart(2, "0");
  const m = String(Math.floor((total % 3600) / 60)).padStart(2, "0");
  return `${h}:${m}`;
}

// 组累计延误 → 行内文案（抽样口径原值，不扩样）：分钟为主，≥1 小时换算
function formatDelay(seconds) {
  const minutes = Number(seconds) / 60;
  if (!Number.isFinite(minutes) || minutes <= 0) return "—";
  if (minutes >= 60) return `${(minutes / 60).toFixed(1)} 小时`;
  return `${Math.max(1, Math.round(minutes))} 分`;
}

function ensureCongestHighlight() {
  if (!congestHighlightManager) {
    congestHighlightManager = new LinkSpeedHighlightManager();
  }
  if (MapRef?.value) {
    congestHighlightManager.attach(MapRef.value);
  }
  return congestHighlightManager;
}

function syncCongestBucket(simSeconds) {
  const data = congestData.value;
  const next = linkSpeedBucketOf(simSeconds, data?.buckets || 96, data?.bucketSeconds || 900);
  if (next !== congestBucket.value) {
    congestBucket.value = next;
  }
}

function stopCongestPolling() {
  if (congestPollTimer) {
    clearTimeout(congestPollTimer);
    congestPollTimer = null;
  }
}

function scheduleCongestPoll() {
  stopCongestPolling();
  congestPollTimer = setTimeout(() => {
    congestPollTimer = null;
    bootstrapCongestion();
  }, LINK_SPEED_POLL_MS);
}

async function bootstrapCongestion() {
  stopCongestPolling();
  if (!props.model) return;
  const seq = ++congestSeq;
  const model = props.model;
  if (congestStatus.value !== "generating") {
    congestStatus.value = "loading";
  }
  try {
    const summary = await getCachedLinkSpeedSummary(model);
    if (seq !== congestSeq || props.model !== model) return;
    if (!summary || summary.status === "generating") {
      congestStatus.value = "generating";
      scheduleCongestPoll();
      return;
    }
    const version = String(summary.generatedAt || summary.cacheVersion || "");
    const buffer = await getCachedLinkSpeedMatrix(model, version);
    if (seq !== congestSeq || props.model !== model) return;
    if (!buffer) {
      congestStatus.value = "generating";
      scheduleCongestPoll();
      return;
    }
    const parsed = getModelDerived(model, "linkSpeedMatrix", () => markRaw(parseLinkSpeedMatrix(buffer)));
    congestSummary.value = markRaw(summary);
    congestData.value = parsed;
    // 自由流基线 O(链路×96) 只算一次，随模型 entry 缓存；组件重挂载/页签往返直接命中
    congestFreeflow.value = getModelDerived(model, "linkSpeedFreeflow", () => buildLinkSpeedFreeflow(parsed));
    ensureCongestHighlight().setData(parsed);
    syncCongestBucket(isPlaying.value ? playbackClock : currentTime.value);
    congestStatus.value = parsed.count > 0 ? "ready" : "empty";
  } catch (error) {
    if (seq !== congestSeq || isCanceledRequest(error)) return;
    // 后端旧版本无该接口/缓存未建：按生成中轮询，部署后自动出榜（与车速图层同一容错口径）
    congestStatus.value = "generating";
    scheduleCongestPoll();
  }
}

function clearCongestFocus() {
  activeCongestKey.value = "";
  congestHighlightManager?.clear();
}

// 点击榜单行：定位到该路段（组内链路联合外接框）+ 高亮描边 + 自动开启车速图层，
// 让地图上能同时看到"这是哪"与"堵成什么颜色"。再点一次取消高亮。
function focusCongestGroup(group) {
  if (activeCongestKey.value === group.key) {
    clearCongestFocus();
    return;
  }
  activeCongestKey.value = group.key;
  if (!LinkSpeedEnabledRef.value) {
    LinkSpeedEnabledRef.value = true; // 矩阵已在缓存，watch 触发的 bootstrap 秒出图
  }
  ensureCongestHighlight().highlight(group.links);
  const data = congestData.value;
  const map = MapRef?.value?.map;
  if (!data || !map?.fitBounds) return;
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const k of group.links) {
    minX = Math.min(minX, data.x1[k], data.x2[k]);
    maxX = Math.max(maxX, data.x1[k], data.x2[k]);
    minY = Math.min(minY, data.y1[k], data.y2[k]);
    maxY = Math.max(maxY, data.y1[k], data.y2[k]);
  }
  if (!Number.isFinite(minX)) return;
  map.fitBounds([mercatorToLngLat(minX, minY), mercatorToLngLat(maxX, maxY)],
    { padding: 120, duration: 600, maxZoom: 15.5 });
}

// 跨桶后榜单整体换血，旧选中/高亮指向的时段已过去，一并清除
watch(congestBucket, clearCongestFocus);

watch(() => props.model, () => {
  // index.vue 以模型名作组件 key，正常走整组件重建；此 watch 兜底热切换：清空旧模型状态重引导
  congestSeq += 1;
  stopCongestPolling();
  clearCongestFocus();
  congestSummary.value = null;
  congestData.value = null;
  congestFreeflow.value = null;
  congestHighlightManager?.setData(null);
  bootstrapCongestion();
});

// 播放/拖动进度条：currentTime 节流回写（120ms），跨 15min 桶时图层整层换色 + 拥堵榜重算
watch(currentTime, (time) => {
  syncCongestBucket(time);
  if (!LinkSpeedEnabledRef.value) return;
  syncLinkSpeedBucket(time);
});

// 地图实例晚于组件就绪时补挂（commit 内部对未挂载地图静默）
watch(() => MapRef?.value, (wrapper) => {
  if (wrapper && linkSpeedManager) {
    linkSpeedManager.attach(wrapper);
    linkSpeedManager.commit();
  }
  if (wrapper && congestHighlightManager) {
    congestHighlightManager.attach(wrapper);
    congestHighlightManager.commit();
  }
});

onMounted(() => {
  ensureTrajectoryLayer();
  // 开关状态跨模型/跨进出页签保留（ref 归 index.vue 持有）：进页即恢复上次的开启态
  if (LinkSpeedEnabledRef.value) {
    bootstrapLinkSpeed();
  }
  // 拥堵榜独立引导：不依赖图层开关，面板进页即出数据
  bootstrapCongestion();
});

onUnmounted(() => {
  // 递增请求序号，使在途的轨迹/分块请求回调全部失效，
  // 避免卸载后异步回调继续写入已销毁组件的 ref 状态。
  loadSeq += 1;
  chunkSeq += 1;
  stopPlayback();
  stopPolling();
  cancelSeekChunkLoad();
  cancelSeekSnapshot();
  cancelScheduledPrefetch();
  cancelSeekRender();
  cancelPrefetchRequests();
  // 中止仍在途的分块请求（含预取），与上面的序号失效互为双保险。
  chunkAbortController?.abort();
  foregroundChunkController?.abort();
  if (trajectoryLayer) {
    trajectoryLayer.dispose();
    trajectoryLayer = null;
  }
  // 车速图层随组件卸载移除（切页签/换模型即卸载）；开关偏好留在 index.vue 的 ref 里
  linkSpeedSeq += 1;
  stopLinkSpeedPolling();
  if (linkSpeedManager) {
    linkSpeedManager.dispose();
    linkSpeedManager = null;
  }
  LinkSpeedStatusRef.value = "idle";
  // 拥堵榜同步清场：失效在途请求、停止轮询、摘掉高亮层
  congestSeq += 1;
  stopCongestPolling();
  if (congestHighlightManager) {
    congestHighlightManager.dispose();
    congestHighlightManager = null;
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

/* ── 轨迹演示控制条：贴地图底部的横向"媒体播放器"（播放/重置 · 倍速 · 时间轴 · 时刻） ── */
.rm-play-bar {
  width: min(760px, 100%);
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 16px;
  min-height: 56px;
  padding: 9px 16px;
  border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
  border-radius: var(--dm2-radius-lg, 16px);
  background: var(--dm2-glass-strong, rgba(255, 255, 255, 0.86));
  box-shadow: var(--dm2-shadow-pop, 0 18px 44px -16px rgba(13, 38, 76, 0.26)),
    var(--dm2-glass-highlight, inset 0 1px 0 rgba(255, 255, 255, 0.72));
  -webkit-backdrop-filter: var(--dm2-glass-blur, blur(14px) saturate(180%));
  backdrop-filter: var(--dm2-glass-blur, blur(14px) saturate(180%));
  color: var(--dm2-ink, #1c2024);
  font-family: var(--dm2-font);
}

/* 播放 / 重置 */
.rm-play-transport {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
}

.rm-play-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: var(--dm2-accent-grad, linear-gradient(135deg, #0a84ff 0%, #0071e3 52%, #0a63cc 100%));
  color: #ffffff;
  cursor: pointer;
  box-shadow: var(--dm2-accent-glow, 0 6px 18px -6px rgba(0, 113, 227, 0.45)), inset 0 1px 0 rgba(255, 255, 255, 0.4);
  transition: transform var(--dm2-dur-fast, 140ms) var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1)),
    box-shadow var(--dm2-dur-fast, 140ms) var(--dm2-ease);


  &:hover:not(:disabled) {
    box-shadow: 0 8px 22px -6px rgba(0, 113, 227, 0.5), inset 0 1px 0 rgba(255, 255, 255, 0.45);
  }

  &:active:not(:disabled) {
    transform: scale(0.94);
  }

  &:focus-visible {
    outline: 2px solid var(--dm2-accent-ring, rgba(0, 113, 227, 0.18));
    outline-offset: 2px;
  }

  &:disabled {
    background: var(--dm2-muted-soft, #98a2b3);
    box-shadow: none;
    cursor: not-allowed;
    opacity: 0.7;
  }
}

.rm-play-reset {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  padding: 0;
  border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
  border-radius: 50%;
  background: var(--dm2-surface, #ffffff);
  color: var(--dm2-ink-soft, #3b4452);
  cursor: pointer;
  transition: color var(--dm2-dur-fast, 140ms) var(--dm2-ease),
    border-color var(--dm2-dur-fast, 140ms) var(--dm2-ease),
    background-color var(--dm2-dur-fast, 140ms) var(--dm2-ease);

  &:hover:not(:disabled) {
    color: var(--dm2-accent, #0071e3);
    border-color: rgba(0, 113, 227, 0.3);
    background: var(--dm2-accent-weak, rgba(0, 113, 227, 0.1));
  }

  &:active:not(:disabled) {
    transform: translateY(1px);
  }

  &:focus-visible {
    outline: 2px solid var(--dm2-accent-ring, rgba(0, 113, 227, 0.18));
    outline-offset: 2px;
  }

  &:disabled {
    color: var(--dm2-muted-soft, #98a2b3);
    cursor: not-allowed;
    opacity: 0.6;
  }
}

/* 倍速：分段控件 */
.rm-play-speed {
  flex: none;
  display: flex;
  padding: 3px;
  border-radius: var(--dm2-radius-pill, 999px);
  background: var(--dm2-surface-sunken, #f4f7fb);
  border: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
}

.rm-play-speed-btn {
  min-width: 38px;
  padding: 5px 10px;
  border: 0;
  border-radius: var(--dm2-radius-pill, 999px);
  background: transparent;
  color: var(--dm2-muted, #667085);
  font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
  font-size: 12.5px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  transition: color var(--dm2-dur-fast, 140ms) var(--dm2-ease),
    background-color var(--dm2-dur-fast, 140ms) var(--dm2-ease),
    box-shadow var(--dm2-dur-fast, 140ms) var(--dm2-ease);

  &:hover:not(.active):not(:disabled) {
    color: var(--dm2-ink, #1c2024);
  }

  &.active {
    background: var(--dm2-surface, #ffffff);
    color: var(--dm2-accent-strong, #005bb5);
    box-shadow: 0 1px 3px rgba(13, 38, 76, 0.14);
  }

  &:focus-visible {
    outline: 2px solid var(--dm2-accent-ring, rgba(0, 113, 227, 0.18));
    outline-offset: 1px;
  }

  &:disabled {
    color: var(--dm2-muted-soft, #98a2b3);
    cursor: not-allowed;
  }
}

/* 时间轴：吃掉中间全部剩余宽度 */
.rm-play-scrub {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  align-items: center;

  :deep(.el-slider) {
    --el-slider-main-bg-color: var(--dm2-accent, #0071e3);
    --el-slider-runway-bg-color: rgba(17, 32, 58, 0.12);
    --el-slider-button-size: 15px;
    --el-slider-button-wrapper-size: 32px;
    --el-slider-height: 5px;
    width: 100%;
    height: 26px;
  }

  :deep(.el-slider__button) {
    border: 2px solid var(--dm2-accent, #0071e3);
    box-shadow: 0 1px 4px rgba(13, 38, 76, 0.22);
  }
}

.rm-play-time {
  flex: none;
  min-width: 70px;
  color: var(--dm2-ink, #1c2024);
  font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
  font-size: 15px;
  font-weight: 780;
  letter-spacing: 0.01em;
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-feature-settings: "tnum";
}

/* 加载 / 生成 / 失败：占满同一条形高度的居中状态行 */
.rm-play-status {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  color: var(--dm2-muted, #667085);
  font-size: 12.5px;
  font-weight: 600;

  &.is-error .rm-play-status-text {
    color: var(--dm2-delete, #c4291c);
  }
}

.rm-play-status-text {
  flex: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.rm-play-spinner {
  flex: none;
  width: 15px;
  height: 15px;
  border-radius: 50%;
  border: 2px solid var(--dm2-accent-weak, rgba(0, 113, 227, 0.1));
  border-top-color: var(--dm2-accent, #0071e3);
  animation: rmPlaySpin 0.8s linear infinite;
}

/* 生成中：进度条吃掉中间空间，指标贴右 */
.rm-play-build-track {
  flex: 1 1 auto;
  min-width: 80px;
  height: 5px;
  border-radius: var(--dm2-radius-pill, 999px);
  background: var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
  overflow: hidden;
}

.rm-play-build-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--dm2-accent-grad, linear-gradient(135deg, #0a84ff, #0071e3));
  transition: width var(--dm2-dur, 240ms) var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1));
}

.rm-play-build-metrics {
  flex: none;
  color: var(--dm2-muted-soft, #98a2b3);
  font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
  font-size: 11.5px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

@keyframes rmPlaySpin {
  to { transform: rotate(360deg); }
}

/* 窄视口（左右面板都展开、可视地图带变窄）：时刻换行到时间轴上方，条形自适应 */
@media (max-width: 1180px) {
  .rm-play-bar {
    gap: 12px;
    padding: 8px 12px;
  }

  .rm-play-time {
    min-width: 62px;
    font-size: 14px;
  }

  .rm-play-speed-btn {
    min-width: 32px;
    padding: 5px 7px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .rm-play-btn,
  .rm-play-reset,
  .rm-play-speed-btn,
  .rm-play-build-fill {
    transition: none;
  }

  .rm-play-spinner {
    animation: none;
  }
}

/* ── 车辆运行监测右侧面板：外壳与四块客流/体检面板同构（扁平、发丝线标题、无卡中卡） ── */
.rm-veh-card {
  width: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  border: 0;
  background: transparent;
  font-family: var(--dm2-font);
}

.rm-veh-card-title {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dm2-space-3, 12px);
  padding: 0 0 10px;
  border-bottom: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));

  h2 {
    margin: 0;
    color: var(--dm2-ink, #1c2024);
    font-size: 20px;
    line-height: 1.18;
    font-weight: 780;
    letter-spacing: -0.01em;
  }
}

/* 播放推进时该徽标持续跳动，表示面板数值随当前时刻实时刷新（唯一一处受控动效） */
.rm-veh-live {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 9px;
  border-radius: var(--dm2-radius-pill, 999px);
  background: var(--dm2-add-weak, rgba(26, 138, 63, 0.1));
  color: var(--dm2-add, #1a8a3f);
  font-size: 11px;
  font-weight: 700;
}

.rm-veh-live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--dm2-add, #1a8a3f);
  animation: rmVehPulse 1.8s var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1)) infinite;
}

/* 主指标：在途车辆总数 */
.rm-veh-hero {
  margin-top: 14px;
  padding: 13px 15px 14px;
  border-radius: var(--dm2-radius, 13px);
  background: var(--dm2-surface-sunken, #f4f7fb);
}

.rm-veh-hero-label {
  color: var(--dm2-muted, #667085);
  font-size: 12px;
  font-weight: 650;
}

.rm-veh-hero-value {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin: 5px 0 0;

  strong {
    color: var(--dm2-ink, #1c2024);
    font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
    font-size: 34px;
    font-weight: 800;
    line-height: 1.05;
    letter-spacing: -0.025em;
    font-variant-numeric: tabular-nums;
  }

  em {
    color: var(--dm2-muted, #667085);
    font-size: 13px;
    font-style: normal;
    font-weight: 650;
  }
}

/* 在途车辆构成：占比条即图例 */
.rm-veh-split {
  margin-top: 14px;
}

.rm-veh-split-bar {
  display: flex;
  height: 10px;
  border-radius: var(--dm2-radius-pill, 999px);
  overflow: hidden;
  background: var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
}

.rm-veh-split-seg {
  min-width: 0;
  height: 100%;
  transition: width var(--dm2-dur, 240ms) var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1));

  & + & {
    box-shadow: inset 1px 0 0 rgba(255, 255, 255, 0.85);
  }
}

.rm-veh-split-legend {
  margin-top: 10px;
}

.rm-veh-split-row {
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr) auto 44px;
  align-items: center;
  gap: 10px;
  padding: 7px 2px;

  & + & {
    border-top: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
  }
}

.rm-veh-swatch {
  width: 12px;
  height: 12px;
  border-radius: 4px;
}

.rm-veh-split-name {
  min-width: 0;
  overflow: hidden;
  color: var(--dm2-ink-soft, #3b4452);
  font-size: 12px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rm-veh-split-val {
  color: var(--dm2-ink, #1c2024);
  font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
  font-size: 15px;
  font-weight: 780;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.rm-veh-split-pct {
  color: var(--dm2-muted, #667085);
  font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
  font-size: 12px;
  font-weight: 700;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

/* 运行指标：累计乘车人数 / 平均车速，靠发丝线分隔的 2×1 */
.rm-veh-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 14px;
  border: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
  border-radius: var(--dm2-radius-sm, 10px);
  background: var(--dm2-surface-sunken, #f4f7fb);
  overflow: hidden;
}

.rm-veh-metric {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 11px 13px;

  &:first-child {
    border-right: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
  }
}

.rm-veh-metric-label {
  color: var(--dm2-muted, #667085);
  font-size: 11px;
  font-weight: 650;
}

.rm-veh-metric-value {
  display: flex;
  align-items: baseline;
  gap: 3px;
  color: var(--dm2-ink, #1c2024);
  font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
  font-size: 20px;
  font-weight: 780;
  line-height: 1.1;
  letter-spacing: -0.015em;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;

  em {
    color: var(--dm2-muted, #667085);
    font-size: 11px;
    font-style: normal;
    font-weight: 650;
  }
}

/* 跟随车辆时的详情卡：同一扁平外壳，令牌统一到 dm2 */
.rm-veh-info-head {
  min-width: 0;

  h2 {
    word-break: break-all;
  }
}

.rm-veh-info-type {
  margin: 3px 0 0;
  color: var(--dm2-muted, #667085);
  font-size: 12px;
  font-weight: 600;
}

.rm-veh-unfollow {
  flex: none;
  padding: 6px 13px;
  border: 1px solid var(--dm2-accent, #0071e3);
  border-radius: var(--dm2-radius-pill, 999px);
  background: var(--dm2-accent-weak, rgba(0, 113, 227, 0.1));
  color: var(--dm2-accent-strong, #005bb5);
  font: 650 12px var(--dm2-font);
  cursor: pointer;
  transition: background-color var(--dm2-dur-fast, 140ms) var(--dm2-ease), transform var(--dm2-dur-fast, 140ms) var(--dm2-ease);

  &:hover {
    background: rgba(0, 113, 227, 0.16);
  }

  &:active {
    transform: translateY(1px);
  }

  &:focus-visible {
    outline: 2px solid var(--dm2-accent-ring, rgba(0, 113, 227, 0.18));
    outline-offset: 2px;
  }
}

.rm-veh-info-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-3, 12px);
  padding-top: 14px;
  scrollbar-width: thin;
  scrollbar-color: rgba(17, 32, 58, 0.18) transparent;

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-thumb {
    border-radius: var(--dm2-radius-pill, 999px);
    background: rgba(17, 32, 58, 0.18);
  }

  .info-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .info-item {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 10px 12px;
    border: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
    border-radius: var(--dm2-radius-sm, 10px);
    background: var(--dm2-surface-sunken, #f4f7fb);

    &.wide {
      grid-column: 1 / -1;
    }

    .label {
      color: var(--dm2-muted, #667085);
      font-size: 11px;
      font-weight: 600;
    }

    .value {
      color: var(--dm2-ink, #1c2024);
      font-size: 13px;
      font-weight: 700;
      line-height: 1.35;
      word-break: break-word;
    }
  }

  .stations-section {
    border-top: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
    padding-top: 12px;
  }

  .section-title {
    margin-bottom: 8px;
    color: var(--dm2-ink-soft, #3b4452);
    font-size: 13px;
    font-weight: 720;
  }

  .station-list {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding-right: 2px;
  }

  .station-row {
    display: grid;
    grid-template-columns: 30px minmax(0, 1fr);
    align-items: center;
    gap: 10px;
    padding: 8px 10px;
    border: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
    border-radius: var(--dm2-radius-sm, 10px);
    background: var(--dm2-surface, #ffffff);

    /* 上一站 / 下一站高亮用与地图车辆点同源的绿/蓝，供跟随时与地图对照 */
    &.previous {
      border-color: rgba(22, 163, 74, 0.36);
      background: rgba(22, 163, 74, 0.08);
    }

    &.next {
      border-color: rgba(37, 99, 235, 0.34);
      background: rgba(37, 99, 235, 0.08);
    }

    .station-index {
      color: var(--dm2-accent, #0071e3);
      font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
      font-size: 12px;
      font-weight: 780;
      text-align: right;
    }

    .station-name {
      min-width: 0;
      color: var(--dm2-ink, #1c2024);
      font-size: 12px;
      font-weight: 650;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  }
}

/* ── 主要拥堵路段 TOP10 ──
   面板宿主是定长 flex 列且 overflow:hidden（与其余四块监测同构，不做外层滚动）：
   状态卡取自然高度（flex:none），拥堵卡吃掉剩余高度（继承 .rm-veh-card 的 flex:1），
   榜单在卡内部滚动——否则状态卡会被压成 0 高、hero 溢出压到榜单上（原 bug）。 */
.rm-veh-status-card {
  flex: none;
}

.rm-congest-card {
  /* flex:1 + min-height:0 继承自 .rm-veh-card，占据状态卡以下的全部空间 */
  padding-top: 16px;
  border-top: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
}

/* 卡内主标题降一级，从属于面板主标题"车辆运行监测" */
.rm-congest-card .rm-veh-card-title h2 {
  font-size: 16.5px;
}

.rm-congest-top-badge {
  margin-left: 8px;
  padding: 2px 7px;
  border-radius: var(--dm2-radius-pill, 999px);
  background: var(--dm2-delete-weak, rgba(196, 41, 28, 0.1));
  color: var(--dm2-delete, #c4291c);
  font-size: 11px;
  font-style: normal;
  font-weight: 780;
  letter-spacing: 0.02em;
  vertical-align: 3px;
}

.rm-congest-window {
  flex: none;
  padding: 3px 9px;
  border-radius: var(--dm2-radius-pill, 999px);
  background: var(--dm2-surface-sunken, #f4f7fb);
  color: var(--dm2-ink-soft, #3b4452);
  font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
  font-size: 11.5px;
  font-weight: 720;
  font-variant-numeric: tabular-nums;
}

.rm-congest-note {
  margin: 9px 0 0;
  color: var(--dm2-muted-soft, #98a2b3);
  font-size: 11px;
  font-weight: 600;
  line-height: 1.45;
}

.rm-congest-state {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 12px;
  padding: 14px 13px;
  border-radius: var(--dm2-radius-sm, 10px);
  background: var(--dm2-surface-sunken, #f4f7fb);
  color: var(--dm2-muted, #667085);
  font-size: 12.5px;
  font-weight: 620;
}

.rm-congest-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 12px 0 0;
  padding: 0 4px 0 0; /* 右留 4px 给滚动条，行不被吃掉 */
  list-style: none;
  scrollbar-width: thin;
  scrollbar-color: rgba(17, 32, 58, 0.18) transparent;

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-thumb {
    border-radius: var(--dm2-radius-pill, 999px);
    background: rgba(17, 32, 58, 0.18);
  }
}

/* 整行即按钮：排名 | 路名+区/段数 | 速度+降幅，点击定位 */
.rm-congest-row {
  width: 100%;
  box-sizing: border-box;
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
  border-radius: var(--dm2-radius-sm, 10px);
  background: var(--dm2-surface, #ffffff);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--dm2-dur-fast, 140ms) var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1)),
    background-color var(--dm2-dur-fast, 140ms) var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1));

  &:hover {
    border-color: rgba(0, 113, 227, 0.3);
    background: var(--dm2-accent-weak, rgba(0, 113, 227, 0.1));
  }

  &:active {
    transform: translateY(1px);
  }

  &:focus-visible {
    outline: 2px solid var(--dm2-accent-ring, rgba(0, 113, 227, 0.18));
    outline-offset: 1px;
  }

  /* 选中（已定位）态：与地图高亮描边同一亮蓝语义 */
  &.active {
    border-color: rgba(33, 102, 243, 0.55);
    background: rgba(33, 102, 243, 0.09);
    box-shadow: inset 2px 0 0 #2166f3;
  }
}

.rm-congest-rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 7px;
  background: var(--dm2-surface-sunken, #f4f7fb);
  color: var(--dm2-muted, #667085);
  font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
  font-size: 12px;
  font-weight: 780;
  font-variant-numeric: tabular-nums;

  /* 前三名：最堵的路段用拥堵红强调 */
  &.is-top {
    background: var(--dm2-delete-weak, rgba(196, 41, 28, 0.1));
    color: var(--dm2-delete, #c4291c);
  }
}

.rm-congest-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rm-congest-name {
  min-width: 0;
  overflow: hidden;
  color: var(--dm2-ink, #1c2024);
  font-size: 12.5px;
  font-weight: 700;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.rm-congest-sub {
  min-width: 0;
  overflow: hidden;
  color: var(--dm2-muted, #667085);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.rm-congest-speed {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.rm-congest-speed-val {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;

  strong {
    color: var(--dm2-ink, #1c2024);
    font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
    font-size: 16px;
    font-weight: 800;
    line-height: 1.1;
    font-variant-numeric: tabular-nums;
  }

  em {
    color: var(--dm2-muted, #667085);
    font-size: 10.5px;
    font-style: normal;
    font-weight: 650;
  }
}

.rm-congest-dot {
  align-self: center;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  box-shadow: inset 0 0 0 1px rgba(17, 32, 58, 0.12);
}

.rm-congest-drop {
  color: var(--dm2-muted-soft, #98a2b3);
  font-family: var(--dm2-font-num, "SF Pro Display", system-ui);
  font-size: 10.5px;
  font-weight: 680;
  font-variant-numeric: tabular-nums;
}

@keyframes rmVehPulse {
  0% { box-shadow: 0 0 0 0 rgba(26, 138, 63, 0.45); }
  70% { box-shadow: 0 0 0 6px rgba(26, 138, 63, 0); }
  100% { box-shadow: 0 0 0 0 rgba(26, 138, 63, 0); }
}

@media (prefers-reduced-motion: reduce) {
  .rm-veh-live-dot {
    animation: none;
  }

  .rm-veh-split-seg,
  .rm-veh-unfollow,
  .rm-congest-row {
    transition: none;
  }
}

</style>
