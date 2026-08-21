import { markRaw } from "vue";
import { dataEvaluation } from "@/api/data.js";
import { isRealDatasource } from "@/utils/realPassengerFlow.js";
import { getFacilityAll, getStationPanel } from "@/api/facility.js";
import { getLineAll, getRoutePanel } from "@/api/route.js";
import { getTransferDict, getTransferEventsBinary, getTransferSummary } from "@/api/transfer.js";
import { getPopulationGridBinary, getPopulationStreets, getPopulationSummary } from "@/api/population.js";
import {
  getTripEndsGridBinary,
  getTripEndsOdGridBinary,
  getTripEndsOdStreets,
  getTripEndsStreets,
  getTripEndsSummary,
} from "@/api/tripEnds.js";
import { getCorridorLinksBinary, getCorridorNames, getCorridorSummary } from "@/api/corridor.js";
import { getLinkSpeedMatrixBinary, getLinkSpeedSummary } from "@/api/linkspeed.js";
import {
  clearPersistedModelBinaries,
  hasModelBinary,
  readModelBinary,
  writeModelBinary,
} from "@/utils/modelBinaryStore.js";

// 缓存的模型数上限（LRU）：监测页当前模型 + 方案编辑父模型 + 少量历史，超出淘汰最久未用的
const MAX_CACHED_SIMULATION_MODELS = 4;
// 真实数据每个日期的线路/站点面板都是大对象。只保留当前日期与少量相邻日期，
// 持久缓存负责跨刷新复用，避免几十个日期同时常驻内存。
const MAX_CACHED_REAL_MODELS = 6;
// lineAll / routePanel / stationPanel 都是模型级大 payload。
// 首次读取在机械硬盘或冷缓存下经常接近 60s，不能沿用全局接口超时。
const HEAVY_MODEL_REQUEST_TIMEOUT_MS = 180_000;

const modelCache = new Map();
const pendingControllers = new Map();
const warmupPromises = new Map();
const warmupRetryTimers = new Map();
const analysisWarmupPromises = new Map();
const analysisWarmupCompleted = new Set();
const analysisWarmupGeneration = new Map();
const persistentReadBypass = new Set();

function modelKey(model) {
  return String(model || "");
}

function hasPendingFor(key) {
  const prefix = `${key}::`;
  for (const pendingKey of pendingControllers.keys()) {
    if (pendingKey.startsWith(prefix)) return true;
  }
  return false;
}

function evictStaleModels() {
  for (const [real, limit] of [[false, MAX_CACHED_SIMULATION_MODELS], [true, MAX_CACHED_REAL_MODELS]]) {
    let keys = Array.from(modelCache.keys()).filter((key) => isRealDatasource(key) === real);
    while (keys.length > limit) {
      // 跳过在途模型，但继续寻找其后的最旧可淘汰项，避免一个慢请求让 LRU 整体失去上限。
      const oldestKey = keys.find((key) => !hasPendingFor(key));
      if (oldestKey == null) break;
      modelCache.delete(oldestKey);
      keys = keys.filter((key) => key !== oldestKey);
    }
  }
}

function entryFor(model) {
  const key = modelKey(model);
  let entry = modelCache.get(key);
  if (!entry) {
    entry = {};
  } else {
    modelCache.delete(key); // Map 插入序即 LRU 序：命中时重插到队尾
  }
  modelCache.set(key, entry);
  evictStaleModels();
  return entry;
}

// 大 payload 入缓存前统一 markRaw：任何组件即使把它放进深层 ref/reactive，
// Vue 也会跳过代理（__v_skip），从源头消除全量数据的深响应式开销。
// 约定：缓存数据只读、只做整值替换（scenarioedit 的编辑走独立 draft.edits，不改此数据）。
function markRawDeepEnough(data) {
  if (data && typeof data === "object") return markRaw(data);
  return data;
}

function controllerKey(model, type) {
  return `${modelKey(model)}::${type}`;
}

function isCanceled(error) {
  return error?.message === "请求已取消"
    || error?.message === "canceled"
    || error?.cause?.message === "canceled"
    || error?.cause?.code === "ERR_CANCELED";
}

function isPanelReady(data, type) {
  if (!data || data.status === "generating") return false;
  if (type === "routePanel") return Boolean(data.routes);
  if (type === "stationPanel") return Boolean(data.stations);
  if (type === "evaluation") return Boolean(data.values);
  return true;
}

function sharedModelRequest(model, type, requestFn) {
  const key = modelKey(model);
  if (!key) return Promise.resolve([]);
  const entry = entryFor(key);
  const dataKey = `${type}Data`;
  const promiseKey = `${type}Promise`;
  if (entry[dataKey]) return Promise.resolve(entry[dataKey]);
  if (entry[promiseKey]) return entry[promiseKey];

  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  if (controller) pendingControllers.set(controllerKey(key, type), controller);

  entry[promiseKey] = requestFn(
    { datasource: key },
    { silentError: true, signal: controller?.signal, timeout: HEAVY_MODEL_REQUEST_TIMEOUT_MS },
  )
    .then((res) => {
      const data = markRawDeepEnough(Array.isArray(res?.data) ? res.data : []);
      entry[dataKey] = data;
      return data;
    })
    .catch((error) => {
      delete entry[dataKey];
      if (!isCanceled(error)) {
        delete entry[promiseKey];
      }
      throw error;
    })
    .finally(() => {
      if (entry[promiseKey]) delete entry[promiseKey];
      const pendingKey = controllerKey(key, type);
      if (pendingControllers.get(pendingKey) === controller) pendingControllers.delete(pendingKey);
      evictStaleModels();
    });

  return entry[promiseKey];
}

// 对象型面板数据（routePanel/stationPanel）：按模型键控缓存 + 并发去重，中止复用同一套 pendingControllers。
// 关键：后端缓存生成中（data.status === "generating"）时不落缓存，直接透传结果，下次调用重新请求。
function sharedModelPanelRequest(model, type, requestFn) {
  const key = modelKey(model);
  if (!key) return Promise.resolve(null);
  const entry = entryFor(key);
  const dataKey = `${type}Data`;
  const promiseKey = `${type}Promise`;
  if (entry[dataKey]) return Promise.resolve(entry[dataKey]);
  if (entry[promiseKey]) return entry[promiseKey];

  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  if (controller) pendingControllers.set(controllerKey(key, type), controller);

  entry[promiseKey] = requestFn(
    { datasource: key },
    { silentError: true, signal: controller?.signal, timeout: HEAVY_MODEL_REQUEST_TIMEOUT_MS },
  )
    .then((res) => {
      const raw = res?.data && typeof res.data === "object" ? res.data : null;
      const data = raw ? markRawDeepEnough(raw) : raw;
      if (data && data.status !== "generating") {
        entry[dataKey] = data;
      }
      return data;
    })
    .catch((error) => {
      delete entry[dataKey];
      if (!isCanceled(error)) {
        delete entry[promiseKey];
      }
      throw error;
    })
    .finally(() => {
      if (entry[promiseKey]) delete entry[promiseKey];
      const pendingKey = controllerKey(key, type);
      if (pendingControllers.get(pendingKey) === controller) pendingControllers.delete(pendingKey);
      evictStaleModels();
    });

  return entry[promiseKey];
}

export function getCachedLineAll(model) {
  return sharedModelRequest(model, "lineAll", getLineAll);
}

export function getCachedFacilityAll(model) {
  return sharedModelRequest(model, "facilityAll", getFacilityAll);
}

export function getCachedRoutePanel(model) {
  return sharedModelPanelRequest(model, "routePanel", getRoutePanel);
}

export function getCachedStationPanel(model) {
  return sharedModelPanelRequest(model, "stationPanel", getStationPanel);
}

/** 首次真实数据预加载完成后，直接灌入所有日期的线路/站点索引缓存。 */
export function primeCachedRealPanels(model, bundle = {}) {
  const key = modelKey(model);
  if (!isRealDatasource(key)) return;
  const entry = entryFor(key);
  if (bundle.routePanel && isPanelReady(bundle.routePanel, "routePanel")) {
    entry.routePanelData = markRawDeepEnough(bundle.routePanel);
  }
  if (bundle.stationPanel && isPanelReady(bundle.stationPanel, "stationPanel")) {
    entry.stationPanelData = markRawDeepEnough(bundle.stationPanel);
  }
  if (bundle.evaluation && bundle.evaluation.status !== "generating") {
    entry["evaluation@全市Data"] = markRawDeepEnough(bundle.evaluation);
  }
}

export function getCachedEvaluation(model, district = "全市") {
  const scope = String(district || "").trim() || "全市";
  return sharedModelPanelRequest(
    model,
    `evaluation@${scope}`,
    (data, config) => dataEvaluation({ ...data, district: scope }, config),
  );
}

export function getCachedTransferSummary(model) {
  return sharedModelPanelRequest(model, "transferSummary", getTransferSummary);
}

export function getCachedTransferDict(model) {
  return sharedModelPanelRequest(model, "transferDict", getTransferDict);
}

export function getCachedPopulationSummary(model) {
  return sharedModelPanelRequest(model, "populationSummary", getPopulationSummary);
}

export function getCachedPopulationStreets(model, version = "") {
  return sharedModelPanelRequest(model, `populationStreets@${String(version)}`, getPopulationStreets);
}

// 大二进制工件统一走三层缓存：页面内存 → IndexedDB → 网络。
// 只有服务端 summary 提供了稳定版本时才读写持久层，以免无法失效。
function sharedPersistentBinaryRequest(model, type, version, requestFn, options = {}) {
  const key = modelKey(model);
  if (!key) return Promise.resolve(null);
  const entry = entryFor(key);
  const versionKey = String(version);
  const compressedPrewarm = options.prewarmCompressed === true;
  // 预热与实际读取不能共用 Promise：预热返回的是轻量就绪标记，页面读取
  // 必须获得真正解压后的 ArrayBuffer。
  const requestVariant = compressedPrewarm ? "@compressed-prewarm" : "";
  const dataKey = `${type}Data@${versionKey}`;
  const promiseKey = `${type}Promise@${versionKey}${requestVariant}`;
  if (!compressedPrewarm && entry[dataKey]) return Promise.resolve(entry[dataKey]);
  if (entry[promiseKey]) return entry[promiseKey];

  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  const pendingType = `${type}@${versionKey}${requestVariant}`;
  const persistenceTypeKey = controllerKey(key, type);
  if (controller) pendingControllers.set(controllerKey(key, pendingType), controller);

  const promise = (async () => {
    if (!persistentReadBypass.has(persistenceTypeKey)) {
      if (compressedPrewarm) {
        // 只读元数据确认压缩工件存在；不从 IndexedDB 取 payload，更不解压。
        if (await hasModelBinary(key, type, versionKey)) {
          return { status: "compressed-ready", type, version: versionKey };
        }
      } else {
        const persisted = await readModelBinary(key, type, versionKey);
        if (persisted instanceof ArrayBuffer && persisted.byteLength > 0) {
          entry[dataKey] = persisted;
          return persisted;
        }
      }
    }

    const response = await requestFn(
      { datasource: key, v: versionKey },
      { silentError: true, signal: controller?.signal, timeout: HEAVY_MODEL_REQUEST_TIMEOUT_MS },
    );
    const buffer = response instanceof ArrayBuffer ? response : response?.data;
    if (!(buffer instanceof ArrayBuffer)) return null;
    if (compressedPrewarm) {
      // 网络响应仅作为压缩写入的瞬时输入；等待写入结束后不保留引用，GC 可立即回收。
      await writeModelBinary(key, type, versionKey, buffer);
      return { status: "compressed-ready", type, version: versionKey };
    }
    entry[dataKey] = buffer;
    // IndexedDB 写入可能触发大块结构化克隆，放到空闲时段，不阻塞首次绘制。
    if (typeof window !== "undefined") {
      runWhenIdle(() => { void writeModelBinary(key, type, versionKey, buffer); });
    }
    return buffer;
  })()
    .catch((error) => {
      if (!compressedPrewarm) delete entry[dataKey];
      if (!isCanceled(error)) {
        delete entry[promiseKey];
      }
      throw error;
    })
    .finally(() => {
      if (entry[promiseKey] === promise) delete entry[promiseKey];
      const pendingKey = controllerKey(key, pendingType);
      if (pendingControllers.get(pendingKey) === controller) pendingControllers.delete(pendingKey);
      evictStaleModels();
    });

  entry[promiseKey] = promise;
  return promise;
}

// 人口栅格二进制：按模型+版本键控的内存/持久缓存 + 并发去重。
export function getCachedPopulationGrid(model, version = "", options = {}) {
  return sharedPersistentBinaryRequest(
    model,
    "population-grid",
    version,
    getPopulationGridBinary,
    options,
  );
}

/** 人口缓存契约升级时只清理人口工件，不影响同模型的线路/站点等大缓存。 */
export function invalidateCachedPopulationBundle(model) {
  const key = modelKey(model);
  const entry = modelCache.get(key);
  if (!key) return;
  if (entry) {
    for (const field of Object.keys(entry)) {
      if (field.startsWith("populationSummary")
          || field.startsWith("populationStreets")
          || field.startsWith("population-grid")) {
        delete entry[field];
      }
    }
  }
  for (const [pendingKey, controller] of pendingControllers.entries()) {
    if (pendingKey.startsWith(`${key}::population`)) {
      controller.abort();
      pendingControllers.delete(pendingKey);
    }
  }
  const persistenceTypeKey = controllerKey(key, "population-grid");
  persistentReadBypass.add(persistenceTypeKey);
  void clearPersistedModelBinaries(key, "population-grid")
    .finally(() => persistentReadBypass.delete(persistenceTypeKey));
}

export function getCachedTripEndsSummary(model) {
  return sharedModelPanelRequest(model, "tripEndsSummary", getTripEndsSummary);
}

export function getCachedTripEndsStreets(model) {
  return sharedModelPanelRequest(model, "tripEndsStreets", getTripEndsStreets);
}

// 出行分布栅格二进制。
export function getCachedTripEndsGrid(model, version = "", options = {}) {
  return sharedPersistentBinaryRequest(model, "tripends-grid", version, getTripEndsGridBinary, options);
}

export function getCachedTripEndsOdStreets(model) {
  return sharedModelPanelRequest(model, "tripEndsOdStreets", getTripEndsOdStreets);
}

// 公交 OD 栅格对二进制。
export function getCachedTripEndsOdGrid(model, version = "", options = {}) {
  return sharedPersistentBinaryRequest(model, "tripends-od-grid", version, getTripEndsOdGridBinary, options);
}

export function getCachedCorridorSummary(model) {
  return sharedModelPanelRequest(model, "corridorSummary", getCorridorSummary);
}

export function getCachedCorridorNames(model) {
  return sharedModelPanelRequest(model, "corridorNames", getCorridorNames);
}

// 走廊路段二进制。
export function getCachedCorridorLinks(model, version = "", options = {}) {
  return sharedPersistentBinaryRequest(model, "corridor-links", version, getCorridorLinksBinary, options);
}

export function getCachedLinkSpeedSummary(model) {
  return sharedModelPanelRequest(model, "linkSpeedSummary", getLinkSpeedSummary);
}

// 链路车速矩阵二进制。
export function getCachedLinkSpeedMatrix(model, version = "", options = {}) {
  return sharedPersistentBinaryRequest(model, "link-speed-matrix", version, getLinkSpeedMatrixBinary, options);
}

// 换乘事件表二进制：按模型键控缓存 ArrayBuffer + 并发去重。
// HTTP 层 ETag/immutable 由后端下发，浏览器缓存自动 304；内存层沿用 LRU entry。
export function getCachedTransferEvents(model, version = "", options = {}) {
  return sharedPersistentBinaryRequest(model, "transfer-events", version, getTransferEventsBinary, options);
}

function runWhenIdle(fn) {
  if (typeof window !== "undefined" && typeof window.requestIdleCallback === "function") {
    window.requestIdleCallback(fn, { timeout: 3000 });
    return;
  }
  setTimeout(fn, 0);
}

function waitForNextIdle() {
  return new Promise((resolve) => runWhenIdle(resolve));
}

function clearWarmupRetry(key) {
  const timer = warmupRetryTimers.get(key);
  if (timer) {
    clearTimeout(timer);
    warmupRetryTimers.delete(key);
  }
}

function scheduleWarmPanelRetry(model, type, loader, attempt = 0) {
  const key = `${modelKey(model)}::${type}::retry`;
  if (!modelKey(model) || warmupRetryTimers.has(key) || attempt >= 90) return;
  const delay = Math.min(15_000, 2_000 + attempt * 750);
  const timer = setTimeout(() => {
    warmupRetryTimers.delete(key);
    loader(model)
      .then((data) => {
        if (!isPanelReady(data, type)) {
          scheduleWarmPanelRetry(model, type, loader, attempt + 1);
        }
      })
      .catch((error) => {
        if (!isCanceled(error)) {
          scheduleWarmPanelRetry(model, type, loader, attempt + 1);
        }
      });
  }, delay);
  warmupRetryTimers.set(key, timer);
}

function warmPanel(model, type, loader) {
  return loader(model)
    .then((data) => {
      if (isPanelReady(data, type)) {
        clearWarmupRetry(`${modelKey(model)}::${type}::retry`);
      } else {
        scheduleWarmPanelRetry(model, type, loader);
      }
      return data;
    })
    .catch((error) => {
      if (!isCanceled(error)) {
        scheduleWarmPanelRetry(model, type, loader);
      }
      return null;
    });
}

// 链路车速包（summary→matrix 连锁）：返回 summary 供 warmPanel 的 generating 重试判定，
// 就绪时顺带把矩阵二进制拉进模型缓存——车辆运行监测开"路段公交车速"开关即出图，不再现场等待
function loadLinkSpeedBundle(model) {
  return getCachedLinkSpeedSummary(model).then(async (summary) => {
    if (shouldLoadBinaryBundle(summary)) {
      const version = String(summary.generatedAt || summary.cacheVersion || "");
      const matrix = await getCachedLinkSpeedMatrix(model, version, { prewarmCompressed: true });
      if (!matrix) return { status: "generating" };
    }
    return summary;
  });
}

// 换乘分析整包（summary→dict+events）也属于模型级交互缓存。
// 必须在模型首次就绪后预取，而不是等用户进入换乘分析再下载事件表；
// summary 仍在生成时只返回状态，由 warmPanel 的统一重试机制继续跟进。
function loadTransferBundle(model) {
  return getCachedTransferSummary(model).then(async (summary) => {
    if (shouldLoadBinaryBundle(summary)) {
      const version = String(summary.version || summary.generatedAt || summary.cacheVersion || "");
      const dict = await getCachedTransferDict(model);
      // summary 可能已落盘而 dict 尚在最后写入阶段，继续交给统一重试，不能把
      // “字典仍生成中”误记成整包就绪。
      if (!dict || dict.status === "generating") return dict || { status: "generating" };
      const events = await getCachedTransferEvents(model, version, { prewarmCompressed: true });
      if (!events) return { status: "generating" };
    }
    return summary;
  });
}

function shouldLoadBinaryBundle(summary) {
  return Boolean(summary)
    && !["generating", "error", "unsupported", "nodata"].includes(summary.status);
}

function binaryBundleVersion(summary) {
  return String(summary?.generatedAt || summary?.cacheVersion || "");
}

async function loadPopulationBundle(model) {
  const summary = await getCachedPopulationSummary(model);
  if (!shouldLoadBinaryBundle(summary)) return summary;
  const version = binaryBundleVersion(summary);
  const [gridResult, streetsResult] = await Promise.allSettled([
    getCachedPopulationGrid(model, version, { prewarmCompressed: true }),
    getCachedPopulationStreets(model, version),
  ]);
  const streets = streetsResult.status === "fulfilled" ? streetsResult.value : null;
  if (gridResult.status !== "fulfilled" || !gridResult.value
      || !streets || streets.status === "generating") return { status: "generating" };
  return summary;
}

async function loadTripEndsBundle(model) {
  const summary = await getCachedTripEndsSummary(model);
  if (!shouldLoadBinaryBundle(summary)) return summary;
  const version = binaryBundleVersion(summary);
  const [gridResult, streetsResult] = await Promise.allSettled([
    getCachedTripEndsGrid(model, version, { prewarmCompressed: true }),
    getCachedTripEndsStreets(model),
  ]);
  const streets = streetsResult.status === "fulfilled" ? streetsResult.value : null;
  if (gridResult.status !== "fulfilled" || !gridResult.value
      || !streets || streets.status === "generating") return { status: "generating" };
  return summary;
}

async function loadTripEndsOdBundle(model) {
  const summary = await getCachedTripEndsSummary(model);
  if (!shouldLoadBinaryBundle(summary)) return summary;
  const version = binaryBundleVersion(summary);
  const [gridResult, streetsResult] = await Promise.allSettled([
    getCachedTripEndsOdGrid(model, version, { prewarmCompressed: true }),
    getCachedTripEndsOdStreets(model),
  ]);
  const streets = streetsResult.status === "fulfilled" ? streetsResult.value : null;
  if (gridResult.status !== "fulfilled" || !gridResult.value
      || !streets || streets.status === "generating") return { status: "generating" };
  return summary;
}

async function loadCorridorBundle(model) {
  const summary = await getCachedCorridorSummary(model);
  if (!shouldLoadBinaryBundle(summary)) return summary;
  const version = binaryBundleVersion(summary);
  const [linksResult, namesResult] = await Promise.allSettled([
    getCachedCorridorLinks(model, version, { prewarmCompressed: true }),
    getCachedCorridorNames(model),
  ]);
  const names = namesResult.status === "fulfilled" ? namesResult.value : null;
  if (linksResult.status !== "fulfilled" || !linksResult.value
      || !names || names.status === "generating") return { status: "generating" };
  return summary;
}

/**
 * 人口、出行分布、公交 OD、走廊四组分析数据逐批预热。
 * 每批都先让出一个浏览器空闲时段，避免同时抢占服务器磁盘、网络和主线程。
 */
export function warmModelAnalysisBinaries(model, options = {}) {
  const key = modelKey(model);
  if (!key) return Promise.resolve(null);
  const { force = false, waitForIdle = waitForNextIdle } = options;
  if (!force && analysisWarmupCompleted.has(key)) return Promise.resolve({ status: "ready" });
  if (analysisWarmupPromises.has(key)) return analysisWarmupPromises.get(key);

  const generation = analysisWarmupGeneration.get(key) || 0;
  const stages = [
    ["populationBundle", loadPopulationBundle],
    ["tripEndsBundle", loadTripEndsBundle],
    ["tripEndsOdBundle", loadTripEndsOdBundle],
    ["corridorBundle", loadCorridorBundle],
  ];
  const promise = (async () => {
    for (const [type, loader] of stages) {
      await waitForIdle();
      if ((analysisWarmupGeneration.get(key) || 0) !== generation) return null;
      await warmPanel(key, type, loader);
    }
    if ((analysisWarmupGeneration.get(key) || 0) === generation) {
      analysisWarmupCompleted.add(key);
      return { status: "ready" };
    }
    return null;
  })().finally(() => {
    if (analysisWarmupPromises.get(key) === promise) analysisWarmupPromises.delete(key);
  });
  analysisWarmupPromises.set(key, promise);
  return promise;
}

// 模型进入前预热客流交互所需的前端缓存：
// - lineAll / facilityAll：地图点选、线路/站点搜索、线网 GeoJSON
// - routePanel：线路着色、选中线路右侧面板、断面客流
// 换乘整包在基础数据完成后单独一批；stationPanel / evaluation、链路车速、
// 四组分析二进制继续在 idle 阶段逐批补齐，避免服务器冷磁盘同时读多个大文件。
export function warmModelInteractionCache(model, options = {}) {
  const key = modelKey(model);
  if (!key) return Promise.resolve(null);
  const generation = analysisWarmupGeneration.get(key) || 0;
  const { includeStationPanel = true, includeEvaluation = true, waitForHeavy = false } = options;
  const warmupKey = `${key}::${includeStationPanel ? "station" : "line"}::${includeEvaluation ? "eval" : "noeval"}::${waitForHeavy ? "wait" : "idle"}`;
  if (warmupPromises.has(warmupKey)) return warmupPromises.get(warmupKey);

  const warmHeavyPanels = async () => {
    await Promise.allSettled([
      includeStationPanel ? warmPanel(key, "stationPanel", getCachedStationPanel) : Promise.resolve(null),
      includeEvaluation ? warmPanel(key, "evaluation", getCachedEvaluation) : Promise.resolve(null),
    ]);
    if ((analysisWarmupGeneration.get(key) || 0) !== generation) return;
    await waitForNextIdle();
    if ((analysisWarmupGeneration.get(key) || 0) !== generation) return;
    await warmPanel(key, "linkSpeed", loadLinkSpeedBundle);
  };

  const promise = Promise.all([
    getCachedLineAll(key),
    getCachedFacilityAll(key),
    getCachedRoutePanel(key),
  ])
    .then(async ([lines, facilities, routePanel]) => {
      // 换乘事件表可能很大，等基础地图数据读完再启动，不与它们抢冷磁盘。
      const transferSummary = await warmPanel(key, "transferBundle", loadTransferBundle);
      const result = { lines, facilities, routePanel, transferSummary };
      if (waitForHeavy) {
        await warmHeavyPanels();
        return result;
      }
      // Vitest/SSR 没有 window，不留后台计时器；生产浏览器才自动继续预热。
      if (typeof window !== "undefined") {
        runWhenIdle(async () => {
          if ((analysisWarmupGeneration.get(key) || 0) !== generation) return;
          await warmHeavyPanels();
          if ((analysisWarmupGeneration.get(key) || 0) !== generation) return;
          await warmModelAnalysisBinaries(key);
        });
      }
      return result;
    })
    .finally(() => {
      if (warmupPromises.get(warmupKey) === promise) warmupPromises.delete(warmupKey);
    });

  warmupPromises.set(warmupKey, promise);
  return promise;
}

// 同步窥视已缓存的整包线路客流面板（未缓存返回 null，不触发请求）。
// 供行政区筛选变化时判断能否纯本地重算，避免重复下载整包。
export function peekCachedRoutePanel(model) {
  return modelCache.get(modelKey(model))?.routePanelData || null;
}

// ---------- 模型级派生数据缓存 ----------
// 目标：把"从整包数据构建索引/排序选项/拓扑"这类只依赖模型的重计算，
// 前移到模型加载后只算一次，组件重挂载/tab 往返时直接命中，不再逐次重建。
// builder 同步执行、结果 markRaw 后随模型 entry 一起 LRU 淘汰。
export function getModelDerived(model, derivedKey, builder) {
  const key = modelKey(model);
  if (!key) return builder();
  const entry = entryFor(key);
  if (!entry.derived) entry.derived = new Map();
  if (entry.derived.has(derivedKey)) return entry.derived.get(derivedKey);
  const value = markRawDeepEnough(builder());
  entry.derived.set(derivedKey, value);
  return value;
}

// 使某个派生键失效（如显示范围等额外输入变化时由调用方主动失效）
export function invalidateModelDerived(model, derivedKey) {
  const entry = modelCache.get(modelKey(model));
  entry?.derived?.delete(derivedKey);
}

// 模型作用域的通用 Map（如线路详情缓存）：随模型 entry 生命周期存活与淘汰，
// 跨组件重挂载共享。limit 超出时按插入序淘汰最旧条目。
export function getModelScopedMap(model, namespace) {
  const key = modelKey(model);
  const entry = entryFor(key);
  if (!entry.scopedMaps) entry.scopedMaps = new Map();
  let map = entry.scopedMaps.get(namespace);
  if (!map) {
    map = new Map();
    entry.scopedMaps.set(namespace, map);
  }
  return map;
}

export function setScopedWithLimit(map, key, value, limit = 80) {
  if (map.has(key)) map.delete(key);
  map.set(key, markRawDeepEnough(value));
  while (map.size > limit) {
    map.delete(map.keys().next().value);
  }
  return value;
}

// 测试与诊断用：当前缓存的模型键（按 LRU 序，队尾最新）
export function __modelCacheKeys() {
  return Array.from(modelCache.keys());
}

export function clearModelDataCache(model) {
  const key = modelKey(model);
  if (!key) return;
  analysisWarmupGeneration.set(key, (analysisWarmupGeneration.get(key) || 0) + 1);
  analysisWarmupCompleted.delete(key);
  analysisWarmupPromises.delete(key);
  modelCache.delete(key);
  for (const pendingKey of Array.from(warmupPromises.keys())) {
    if (pendingKey.startsWith(`${key}::`)) {
      warmupPromises.delete(pendingKey);
    }
  }
  for (const pendingKey of Array.from(warmupRetryTimers.keys())) {
    if (pendingKey.startsWith(`${key}::`)) {
      clearWarmupRetry(pendingKey);
    }
  }
  for (const [pendingKey, controller] of pendingControllers.entries()) {
    if (pendingKey.startsWith(`${key}::`)) {
      controller.abort();
      pendingControllers.delete(pendingKey);
    }
  }
}

export function abortOtherModelDataRequests(activeModel) {
  const activeKey = modelKey(activeModel);
  for (const key of new Set([
    ...modelCache.keys(),
    ...analysisWarmupPromises.keys(),
    ...analysisWarmupCompleted.values(),
  ])) {
    if (key !== activeKey) {
      analysisWarmupGeneration.set(key, (analysisWarmupGeneration.get(key) || 0) + 1);
      analysisWarmupPromises.delete(key);
      analysisWarmupCompleted.delete(key);
    }
  }
  for (const [pendingKey, controller] of pendingControllers.entries()) {
    if (!pendingKey.startsWith(`${activeKey}::`)) {
      controller.abort();
      pendingControllers.delete(pendingKey);
    }
  }
}
