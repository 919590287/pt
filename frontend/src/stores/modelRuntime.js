import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { getModelList, getSchemeList, loadModel } from "@/api/scheme.js";
import { useModelSelectionStore } from "@/stores/modelSelection.js";
import { isModelUsable, unifiedModelProgress } from "@/utils/modelLoadProgress.js";
import { clearModelDataCache } from "@/utils/modelDataCache.js";

/**
 * 目标模型就绪状态：
 * - 仅依赖模型的页面由 MapLayout 门禁等待当前目标模型；
 * - 数据管理不依赖运行时模型，不会因首次大模型加载而被阻断；
 * - 本 store 负责：拉取方案/模型列表、自动挑选并触发首个模型的后台加载、
 *   高频轮询直到就绪、就绪后降频心跳（感知"全部被卸载"后重新亮门禁）。
 */
export const useModelRuntimeStore = defineStore("modelRuntime", () => {
  const schemes = ref([]);
  const modelsByScheme = ref({});
  const gateScheme = ref("");
  const gateTarget = ref("");
  const bootstrapped = ref(false);
  const gateError = ref("");
  const isSwitchingTarget = ref(false);

  let booting = false;
  let modelDemand = false;
  let pollSeq = 0;
  let pollTimer = 0;
  let heartbeatTimer = 0;
  let schemesPromise = null;
  let schemesFetchedAt = 0;
  const modelPromises = new Map();
  const modelFetchedAt = new Map();
  const CATALOG_FRESH_MS = 750;

  const allModels = computed(() => Object.values(modelsByScheme.value).flat());
  const gateModels = computed(() => modelsByScheme.value[gateScheme.value] || []);
  const gateModel = computed(() => gateModels.value.find((item) => item.name === gateTarget.value) || null);
  const anyModelReady = computed(() => allModels.value.some(isModelUsable));
  // 门禁只服从用户当前目标；其他方案中任意模型 ready 不能误放行到错误数据源。
  const gateVisible = computed(() => !isModelUsable(gateModel.value));
  const gateProgress = computed(() => unifiedModelProgress(gateModel.value));

  function restoredSelection() {
    try {
      return useModelSelectionStore().getSelection("datavisualization");
    } catch {
      return { sourceMode: "simulation", scheme: "", model: "" };
    }
  }

  function rememberSelection(scheme, model) {
    try {
      const current = restoredSelection();
      useModelSelectionStore().setSelection("datavisualization", {
        // 模型门禁只记忆下次切回仿真时使用的方案/模型，不得覆盖
        // 用户当前选中的真实数据模式与真实日期。
        sourceMode: current.sourceMode,
        scheme,
        model,
        realServiceDate: current.realServiceDate,
      });
    } catch {
      /* sessionStorage 不可用时忽略 */
    }
  }

  async function fetchSchemes(options = {}) {
    const now = Date.now();
    if (!options.force && schemesFetchedAt > 0 && now - schemesFetchedAt < CATALOG_FRESH_MS) {
      return schemes.value;
    }
    if (schemesPromise) return schemesPromise;
    schemesPromise = (async () => {
      const res = await getSchemeList(undefined, { silentError: true });
      schemes.value = Array.isArray(res?.data) ? res.data : [];
      schemesFetchedAt = Date.now();
      return schemes.value;
    })();
    try {
      return await schemesPromise;
    } finally {
      schemesPromise = null;
    }
  }

  async function fetchModels(scheme, options = {}) {
    if (!scheme) return [];
    const now = Date.now();
    const fetchedAt = modelFetchedAt.get(scheme) || 0;
    if (!options.force && fetchedAt > 0 && now - fetchedAt < CATALOG_FRESH_MS) {
      return modelsByScheme.value[scheme] || [];
    }
    const inFlight = modelPromises.get(scheme);
    if (inFlight) return inFlight;
    const request = (async () => {
      const res = await getModelList({ schemeName: scheme }, { silentError: true });
      const list = Array.isArray(res?.data) ? res.data : [];
      const previousByName = new Map(
        (modelsByScheme.value[scheme] || []).map((item) => [item?.name, item]),
      );
      for (const item of list) {
        const previous = previousByName.get(item?.name);
        if (!previous) continue;
        const loadGenerationChanged = Number(previous.loadVersion || 0) !== Number(item.loadVersion || 0);
        const cacheGenerationChanged = Number(previous.cacheGeneratedAt || 0) !== Number(item.cacheGeneratedAt || 0);
        if (loadGenerationChanged || cacheGenerationChanged) clearModelDataCache(item.name);
      }
      modelsByScheme.value = { ...modelsByScheme.value, [scheme]: list };
      modelFetchedAt.set(scheme, Date.now());
      return list;
    })();
    modelPromises.set(scheme, request);
    try {
      return await request;
    } finally {
      if (modelPromises.get(scheme) === request) modelPromises.delete(scheme);
    }
  }

  async function refreshAllSchemes() {
    const list = schemes.value.length ? schemes.value : await fetchSchemes();
    await Promise.all(list.map((scheme) => fetchModels(scheme)));
  }

  function pickTargetModel(list, preferredName = "") {
    if (!Array.isArray(list) || !list.length) return null;
    return (
      list.find((item) => item.name === preferredName)
      || list.find((item) => isModelUsable(item))
      || list.find((item) => item.isDefault === true || item.default === true)
      // 缓存已就绪的模型只差本体加载，最快能把平台"点亮"
      || list.find((item) => item.cacheStatus === "ready")
      || list[0]
    );
  }

  function pollDelay(attempt) {
    if (attempt < 20) return 1000;
    if (attempt < 60) return 2000;
    return 5000;
  }

  function stopPolling() {
    pollSeq += 1;
    if (pollTimer) {
      clearTimeout(pollTimer);
      pollTimer = 0;
    }
  }

  function startGatePolling() {
    if (!modelDemand) return;
    stopPolling();
    const seq = pollSeq;
    let attempt = 0;
    const tick = async () => {
      if (seq !== pollSeq || !modelDemand) return;
      if (typeof document !== "undefined" && document.visibilityState === "hidden") {
        pollTimer = setTimeout(tick, 3000);
        return;
      }
      try {
        if (gateScheme.value) {
          await fetchModels(gateScheme.value);
        }
        if (seq !== pollSeq) return;
        if (isModelUsable(gateModel.value)) {
          onGateOpened();
          return;
        }
        // 目标模型被卸载/删除时保持原选择并显式报错，禁止静默切到另一数据源。
        if (gateTarget.value && !gateModels.value.some((item) => item.name === gateTarget.value)) {
          gateError.value = `目标模型不存在或已被移除：${gateTarget.value}`;
          stopPolling();
          return;
        }
      } catch (error) {
        gateError.value = error?.message || "模型状态刷新失败";
        stopPolling();
        return;
      }
      attempt += 1;
      pollTimer = setTimeout(tick, pollDelay(attempt));
    };
    tick();
  }

  function startHeartbeat() {
    if (!modelDemand || heartbeatTimer) return;
    heartbeatTimer = setInterval(async () => {
      if (!modelDemand) return;
      if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
      try {
        if (gateScheme.value) {
          await fetchModels(gateScheme.value);
        }
        if (gateVisible.value && !pollTimer) {
          // 当前目标被卸载：门禁重新亮起，恢复快轮询并自动补加载。
          const target = pickTargetModel(gateModels.value, gateTarget.value);
          if (target) await activateTarget(gateScheme.value || schemes.value[0] || "", target.name);
          startGatePolling();
        }
      } catch (error) {
        gateError.value = error?.message || "模型状态心跳失败";
      }
    }, 30_000);
  }

  function onGateOpened() {
    stopPolling();
    gateError.value = "";
    if (gateScheme.value && gateTarget.value) {
      const target = gateModels.value.find((item) => item.name === gateTarget.value);
      if (isModelUsable(target)) {
        rememberSelection(gateScheme.value, gateTarget.value);
      }
    }
  }

  async function activateTarget(scheme, modelName) {
    gateScheme.value = scheme;
    gateTarget.value = modelName || "";
    gateError.value = "";
    if (!modelName) return;
    const item = (modelsByScheme.value[scheme] || []).find((model) => model.name === modelName);
    if (isModelUsable(item)) return;
    isSwitchingTarget.value = true;
    try {
      // 同名模型重载/缓存重建前先清除浏览器派生缓存；完成后的 loadVersion/
      // cacheGeneratedAt 变化还会在 fetchModels 中再次兜底，避免本会话展示旧评价值。
      clearModelDataCache(modelName);
      await loadModel({ name: modelName }, { silentError: true });
    } catch (error) {
      gateError.value = error?.message || "模型后台加载启动失败，请重试";
      throw error;
    } finally {
      isSwitchingTarget.value = false;
    }
  }

  /** 门禁面板里手动切换方案 */
  async function selectGateScheme(scheme) {
    if (!scheme || scheme === gateScheme.value) return;
    gateScheme.value = scheme;
    gateTarget.value = "";
    const list = await fetchModels(scheme);
    const target = pickTargetModel(list, restoredSelection().model);
    if (target) await activateTarget(scheme, target.name);
  }

  /** 门禁面板里手动切换目标模型 */
  async function selectGateModel(modelName) {
    if (!modelName || modelName === gateTarget.value) return;
    await activateTarget(gateScheme.value, modelName);
  }

  async function retryGateLoad() {
    if (!gateTarget.value) return;
    await activateTarget(gateScheme.value, gateTarget.value);
  }

  async function bootstrap() {
    modelDemand = true;
    if (booting) {
      return;
    }
    if (bootstrapped.value) {
      booting = true;
      try {
        startHeartbeat();
        if (gateVisible.value) {
          const target = pickTargetModel(gateModels.value, gateTarget.value);
          if (target) await activateTarget(gateScheme.value, target.name);
          startGatePolling();
        }
      } finally {
        booting = false;
      }
      return;
    }
    booting = true;
    try {
      const restored = restoredSelection();
      const schemeList = await fetchSchemes();
      if (!schemeList.length) {
        gateError.value = "";
        bootstrapped.value = true;
        startGatePolling();
        startHeartbeat();
        return;
      }
      gateScheme.value = schemeList.includes(restored.scheme) ? restored.scheme : schemeList[0];
      // 先只取目标方案并立即触发模型加载；其余方案目录放到后台补齐，避免首开时
      // 等所有模型的缓存状态扫描完成后才发送 loadModel。
      await fetchModels(gateScheme.value);
      bootstrapped.value = true;
      const target = pickTargetModel(gateModels.value, restored.model);
      if (target) {
        gateTarget.value = target.name;
      }
      if (isModelUsable(target)) {
        onGateOpened();
        startHeartbeat();
      } else {
        if (target) {
          await activateTarget(gateScheme.value, target.name);
        }
        startGatePolling();
        startHeartbeat();
      }
      // 其他方案在用户真正切换时才取目录，避免首载期间对外置盘做无关缓存校验。
    } catch (error) {
      gateError.value = error?.message || "模型目录初始化失败";
      bootstrapped.value = true;
      throw error;
    } finally {
      booting = false;
    }
  }

  /** 离开模型依赖页时停止轮询/心跳，数据管理不会在后台偷偷重载大模型。 */
  function pauseModelDemand() {
    modelDemand = false;
    stopPolling();
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = 0;
    }
  }

  return {
    schemes,
    modelsByScheme,
    gateScheme,
    gateTarget,
    bootstrapped,
    gateError,
    isSwitchingTarget,
    allModels,
    anyModelReady,
    gateVisible,
    gateModels,
    gateModel,
    gateProgress,
    fetchSchemes,
    fetchModels,
    refreshAllSchemes,
    bootstrap,
    pauseModelDemand,
    selectGateScheme,
    selectGateModel,
    retryGateLoad,
  };
});
