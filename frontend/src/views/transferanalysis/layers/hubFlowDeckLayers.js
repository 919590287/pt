/**
 * 选中换乘枢纽后的 Deck 两段式接驳链。
 *
 * 公交→地铁：公交上车站 → 接驳公交下车站 → 地铁站。
 * 地铁→公交：地铁站 → 接驳公交上车站 → 公交下车站。
 *
 * 公交乘车段与站间接驳段保持独立，借助共享的 transferStop + direction 同步光带相位，
 * 让光带先走完第一段、再进入第二段。动效只表示方向与链路先后，不表示真实车辆速度。
 */
import { PathStyleExtension } from "@deck.gl/extensions";
import { TripsLayer } from "@deck.gl/geo-layers";
import { PathLayer } from "@deck.gl/layers";
import { MAP_THEME, hexToRgbArray } from "@/utils/mapTheme.js";
import { curvedLineCoordinates } from "@/views/datavisualization/utils/flowCurves.js";
import { removeSharedDeckLayer, setSharedDeckLayer } from "@/views/datavisualization/layers/deckOverlayRegistry.js";

const STATIC_LAYER_KEY = "ta-hub-flow-deck";
const MOTION_LAYER_KEY = "ta-hub-flow-motion";
// Deck 只负责线；统一站点符号由 MapLibre 的 ta-stops / ta-hubs 画在其上方。
const BEFORE_LAYER_ID = "ta-stops";
const MAX_RIDE_FLOWS = 32;
const MAX_TRANSFER_FLOWS = 96;
const MAX_ANIMATED_CHAINS = 8;
const FRAME_INTERVAL_MS = 33;
const FLOW_CYCLE_MS = 6200;
const FLOW_CYCLE_UNITS = 100;
const DASH_EXTENSION = new PathStyleExtension({ dash: true, highPrecisionDash: true });

function validCoord(value) {
  return Array.isArray(value)
    && value.length >= 2
    && Number.isFinite(Number(value[0]))
    && Number.isFinite(Number(value[1]));
}

function sameCoord(a, b) {
  return Math.abs(a[0] - b[0]) < 1e-10 && Math.abs(a[1] - b[1]) < 1e-10;
}

function stableHash(value) {
  const text = String(value ?? "");
  let hash = 2166136261;
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

function addCandidate(target, candidate) {
  const flow = Number(candidate.flow);
  if (!Number.isFinite(flow) || flow <= 0 || !validCoord(candidate.source) || !validCoord(candidate.target)) return;
  if (sameCoord(candidate.source, candidate.target)) return;
  target.push({
    ...candidate,
    flow,
    source: [Number(candidate.source[0]), Number(candidate.source[1])],
    target: [Number(candidate.target[0]), Number(candidate.target[1])],
  });
}

function directionalPalette() {
  return {
    busToMetro: hexToRgbArray(MAP_THEME.transfer.busToMetro),
    metroToBus: hexToRgbArray(MAP_THEME.transfer.metroToBus),
  };
}

function decorateStage(candidates, stage, maxFlows, palette) {
  const limited = candidates
    .sort((a, b) => b.flow - a.flow)
    .slice(0, Math.max(1, Number(maxFlows) || 1));
  const maxFlow = limited.reduce((maximum, item) => Math.max(maximum, item.flow), 0) || 1;
  return limited
    .map((item) => {
      const hash = stableHash(`${stage}:${item.direction}:${item.sourceIndex}:${item.targetIndex}`);
      const importance = Math.sqrt(item.flow / maxFlow);
      const curvature = stage === "ride" ? 0.075 + (hash % 7) * 0.008 : 0.035 + (hash % 3) * 0.006;
      return {
        ...item,
        stage,
        path: curvedLineCoordinates(item.source, item.target, { curvature, segments: stage === "ride" ? 36 : 20, side: 1 }),
        color: palette[item.direction],
        importance,
        width: stage === "ride" ? 0.85 + 2.25 * importance : 1.45 + 2.35 * importance,
      };
    })
    .sort((a, b) => a.flow - b.flow);
}

/** 构建可独立编码、又能在节点处连续衔接的两类 Deck 路径。 */
export function buildHubFlowPaths({
  tripLinks,
  transferLinks,
  busStopCoord,
  metroStopCoord,
  maxRideFlows = MAX_RIDE_FLOWS,
  maxTransferFlows = MAX_TRANSFER_FLOWS,
}) {
  if (typeof busStopCoord !== "function" || typeof metroStopCoord !== "function") return [];
  const rides = [];
  const transfers = [];

  for (const link of Array.isArray(tripLinks) ? tripLinks : []) {
    const origin = Number(link?.originBusStop);
    const destination = Number(link?.destinationBusStop);
    const originCoord = busStopCoord(origin);
    const destinationCoord = busStopCoord(destination);
    addCandidate(rides, {
      direction: "busToMetro",
      flow: link?.b2m,
      source: originCoord,
      target: destinationCoord,
      sourceIndex: origin,
      targetIndex: destination,
      externalStop: origin,
      transferStop: destination,
    });
    addCandidate(rides, {
      direction: "metroToBus",
      flow: link?.m2b,
      source: originCoord,
      target: destinationCoord,
      sourceIndex: origin,
      targetIndex: destination,
      externalStop: destination,
      transferStop: origin,
    });
  }

  for (const link of Array.isArray(transferLinks) ? transferLinks : []) {
    const busStop = Number(link?.busStop);
    const metroStop = Number(link?.metroStop);
    const busCoord = busStopCoord(busStop);
    const metroCoord = metroStopCoord(metroStop);
    addCandidate(transfers, {
      direction: "busToMetro",
      flow: link?.b2m,
      source: busCoord,
      target: metroCoord,
      sourceIndex: busStop,
      targetIndex: metroStop,
      transferStop: busStop,
      metroStop,
      avgSec: Number(link?.avgSec) || 0,
    });
    addCandidate(transfers, {
      direction: "metroToBus",
      flow: link?.m2b,
      source: metroCoord,
      target: busCoord,
      sourceIndex: metroStop,
      targetIndex: busStop,
      transferStop: busStop,
      metroStop,
      avgSec: Number(link?.avgSec) || 0,
    });
  }

  const palette = directionalPalette();
  // 公交段先画，短接驳段后画，节点附近的语义更明确。
  return [
    ...decorateStage(rides, "ride", maxRideFlows, palette),
    ...decorateStage(transfers, "transfer", maxTransferFlows, palette),
  ];
}

function pathLayer(id, data, overrides = {}) {
  return new PathLayer({
    id,
    data,
    beforeId: BEFORE_LAYER_ID,
    getPath: (item) => item.path,
    getColor: (item) => item.color,
    getWidth: (item) => item.width,
    widthUnits: "pixels",
    widthMinPixels: 1,
    widthMaxPixels: 24,
    capRounded: true,
    jointRounded: true,
    pickable: false,
    parameters: { depthTest: false },
    ...overrides,
  });
}

function staticLayers(flows) {
  const rides = flows.filter((item) => item.stage === "ride");
  const transfers = flows.filter((item) => item.stage === "transfer");
  return [
    // 外围公交乘车段只保留连续虚线；不画黑描边和全线光晕，避免形成放射状粗管。
    pathLayer("ta-hub-ride-dashed", rides, {
      getColor: (item) => [...item.color, 96 + Math.round(item.importance * 74)],
      getWidth: (item) => item.width,
      widthMinPixels: 0.65,
      getDashArray: [3.1, 2.4],
      dashGapPickable: false,
      extensions: [DASH_EXTENSION],
    }),
    // 站间接驳段是第二层语义：实线、稍高对比，但仍不使用大面积常亮光晕。
    pathLayer("ta-hub-transfer-core", transfers, {
      getColor: (item) => [...item.color, 176 + Math.round(item.importance * 58)],
    }),
    pathLayer("ta-hub-transfer-hot-core", transfers, {
      getColor: [229, 247, 255, 174],
      getWidth: (item) => Math.max(0.55, item.width * 0.2),
      widthMinPixels: 0.55,
    }),
  ];
}

function stageTimestamps(pointCount, start, end) {
  if (pointCount <= 1) return [start];
  return Array.from({ length: pointCount }, (_, index) => start + (end - start) * (index / (pointCount - 1)));
}

/**
 * 将成对的公交乘车段和站间接驳段合成 TripsLayer 轨迹。
 * 接驳站坐标重复一次并留出 7 个时间单位，形成可见的断点/停顿。
 */
export function buildHubFlowMotionTrips(flows, maxChains = MAX_ANIMATED_CHAINS) {
  const allFlows = Array.isArray(flows) ? flows : [];
  const transfers = new Map(
    allFlows
      .filter((item) => item.stage === "transfer")
      .map((item) => [`${item.direction}:${item.transferStop}`, item]),
  );
  const rides = allFlows
    .filter((item) => item.stage === "ride" && transfers.has(`${item.direction}:${item.transferStop}`))
    .sort((a, b) => b.flow - a.flow)
    .slice(0, Math.max(1, Number(maxChains) || 1));
  const trips = [];

  for (const ride of rides) {
    const transfer = transfers.get(`${ride.direction}:${ride.transferStop}`);
    const busToMetro = ride.direction === "busToMetro";
    const first = busToMetro ? ride : transfer;
    const second = busToMetro ? transfer : ride;
    const firstEnd = busToMetro ? 22 : 12;
    const secondStart = firstEnd + 7;
    const secondEnd = 41;
    const connector = first.path[first.path.length - 1];
    const path = [...first.path, connector, ...second.path.slice(1)];
    const timestamps = [
      ...stageTimestamps(first.path.length, 0, firstEnd),
      secondStart,
      ...stageTimestamps(second.path.length, secondStart, secondEnd).slice(1),
    ];
    const phase = stableHash(`${ride.direction}:${ride.externalStop}:${ride.transferStop}`) % FLOW_CYCLE_UNITS;

    // 复制前后相邻周期，保证 currentTime 回绕时拖尾不会突然消失。
    for (const cycleShift of [-FLOW_CYCLE_UNITS, 0, FLOW_CYCLE_UNITS]) {
      trips.push({
        ...ride,
        id: `${ride.direction}:${ride.externalStop}:${ride.transferStop}:${cycleShift}`,
        path,
        timestamps: timestamps.map((time) => time + phase + cycleShift),
        motionWidth: 1 + ride.importance * 1.55,
      });
    }
  }
  return trips;
}

function motionLayers(trips, currentTime) {
  const base = {
    beforeId: BEFORE_LAYER_ID,
    data: trips,
    getPath: (item) => item.path,
    getTimestamps: (item) => item.timestamps,
    currentTime,
    fadeTrail: true,
    widthUnits: "pixels",
    widthMinPixels: 0.7,
    widthMaxPixels: 12,
    capRounded: true,
    jointRounded: true,
    pickable: false,
    parameters: { depthTest: false },
  };
  return [
    // 三层都只存在于短拖尾范围内：宽而极淡的 halo、方向色主体、短小白色高光头。
    new TripsLayer({
      ...base,
      id: "ta-hub-flow-band-bloom",
      trailLength: 7.5,
      getColor: (item) => [...item.color, 24],
      getWidth: (item) => item.motionWidth + 5.5,
    }),
    new TripsLayer({
      ...base,
      id: "ta-hub-flow-band-trail",
      trailLength: 4.8,
      getColor: (item) => [...item.color, 148],
      getWidth: (item) => item.motionWidth + 0.9,
    }),
    new TripsLayer({
      ...base,
      id: "ta-hub-flow-band-hot",
      trailLength: 1.35,
      getColor: [235, 250, 255, 236],
      getWidth: (item) => Math.max(0.7, item.motionWidth * 0.42),
    }),
  ];
}

export class HubFlowDeckLayerManager {
  constructor(mapWrapper) {
    this.mapWrapper = mapWrapper;
    this.flows = [];
    this.motionTrips = [];
    this.visible = true;
    this.motionEnabled = true;
    this.frame = null;
    this.lastFrameAt = 0;
    this.reducedMotion = typeof window !== "undefined"
      && window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches;
  }

  setFlows(flows) {
    this.flows = Array.isArray(flows) ? flows : [];
    this.motionTrips = buildHubFlowMotionTrips(this.flows);
    this.render();
  }

  setVisible(visible) {
    const next = Boolean(visible);
    if (this.visible === next) return;
    this.visible = next;
    this.render();
  }

  setMotionEnabled(enabled) {
    const next = Boolean(enabled);
    if (this.motionEnabled === next) return;
    this.motionEnabled = next;
    this.renderMotion(performance.now());
    this.syncAnimationLoop();
  }

  render() {
    if (!this.visible || !this.flows.length) {
      removeSharedDeckLayer(this.mapWrapper, STATIC_LAYER_KEY);
      removeSharedDeckLayer(this.mapWrapper, MOTION_LAYER_KEY);
      this.stopAnimation();
      return;
    }
    setSharedDeckLayer(this.mapWrapper, STATIC_LAYER_KEY, staticLayers(this.flows), 40);
    this.renderMotion(performance.now());
    this.syncAnimationLoop();
  }

  renderMotion(now) {
    if (!this.visible || !this.flows.length || !this.motionEnabled || this.reducedMotion) {
      removeSharedDeckLayer(this.mapWrapper, MOTION_LAYER_KEY);
      return;
    }
    const currentTime = ((now / FLOW_CYCLE_MS) * FLOW_CYCLE_UNITS) % FLOW_CYCLE_UNITS;
    setSharedDeckLayer(this.mapWrapper, MOTION_LAYER_KEY, motionLayers(this.motionTrips, currentTime), 41);
  }

  syncAnimationLoop() {
    if (this.reducedMotion || !this.motionEnabled || !this.visible || !this.flows.length) {
      this.stopAnimation();
      return;
    }
    if (this.frame != null || typeof requestAnimationFrame !== "function") return;
    const tick = (now) => {
      this.frame = null;
      if (!this.visible || !this.motionEnabled || !this.flows.length) return;
      if (now - this.lastFrameAt >= FRAME_INTERVAL_MS && (typeof document === "undefined" || !document.hidden)) {
        this.lastFrameAt = now;
        this.renderMotion(now);
      }
      this.frame = requestAnimationFrame(tick);
    };
    this.frame = requestAnimationFrame(tick);
  }

  stopAnimation() {
    if (this.frame != null && typeof cancelAnimationFrame === "function") cancelAnimationFrame(this.frame);
    this.frame = null;
  }

  clear() {
    this.stopAnimation();
    this.flows = [];
    this.motionTrips = [];
    removeSharedDeckLayer(this.mapWrapper, STATIC_LAYER_KEY);
    removeSharedDeckLayer(this.mapWrapper, MOTION_LAYER_KEY);
  }
}
