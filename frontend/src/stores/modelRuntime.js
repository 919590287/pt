import { defineStore } from "pinia";
import { computed, ref } from "vue";
import { getModelList, getSchemeList, loadModel } from "@/api/scheme.js";
import { useModelSelectionStore } from "@/stores/modelSelection.js";
import { isModelUsable, unifiedModelProgress } from "@/utils/modelLoadProgress.js";

/**
 * 全局模型就绪状态：
 * - 平台打开后，在任何一个模型（loadStatus + cacheStatus=ready）就绪之前，
 *   所有业务页面（数据管理 → 配车测算）都由 MapLayout 的全局门禁挡住；
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
  let pollSeq = 0;
  let pollTimer = 0;
  let heartbeatTimer = 0;

  const allModels = computed(() => Object.values(modelsByScheme.value).flat());
  const anyModelReady = computed(() => allModels.value.some(isModelUsable));
  const gateVisible = computed(() => !anyModelReady.value);
  const gateModels = computed(() => modelsByScheme.value[gateScheme.value] || []);
  const gateModel = computed(() => gateModels.value.find((item) => item.name === gateTarget.value) || null);
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
      useModelSelectionStore().setSelection("datavisualization", {
        sourceMode: "simulation",
        scheme,
        model,
      });
    } catch {
      /* sessionStorage 不可用时忽略 */
    }
  }

  async function fetchSchemes() {
    const res = await getSchemeList(undefined, { silentError: true });
    schemes.value = Array.isArray(res?.data) ? res.data : [];
    return schemes.value;
  }

  async function fetchModels(scheme) {
    if (!scheme) return [];
    const res = await getModelList({ schemeName: scheme }, { silentError: true });
    const list = Array.isArray(res?.data) ? res.data : [];
    modelsByScheme.value = { ...modelsByScheme.value, [scheme]: list };
    return list;
  }

  async function refreshAllSchemes() {
    const list = schemes.value.length ? schemes.value : await fetchSchemes();
    await Promise.all(list.map((scheme) => fetchModels(scheme).catch(() => [])));
  }

  function pickTargetModel(list, preferredName = "") {
    if (!Array.isArray(list) || !list.length) return null;
    return (
      list.find((item) => item.name === preferredName)
      || list.find((item) => isModelUsable(item))
      || list.find((item) => item.isDefault)
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
    stopPolling();
    const seq = pollSeq;
    let attempt = 0;
    const tick = async () => {
      if (seq !== pollSeq) return;
      if (typeof document !== "undefined" && document.visibilityState === "hidden") {
        pollTimer = setTimeout(tick, 3000);
        return;
      }
      try {
        if (gateScheme.value) {
          await fetchModels(gateScheme.value);
        }
        if (seq !== pollSeq) return;
        if (anyModelReady.value) {
          onGateOpened();
          return;
        }
        // 目标模型可能被别人卸载/删除，兜底重挑
        if (gateTarget.value && !gateModels.value.some((item) => item.name === gateTarget.value)) {
          const next = pickTargetModel(gateModels.value);
          if (next) await activateTarget(gateScheme.value, next.name);
        }
      } catch {
        /* 静默重试 */
      }
      attempt += 1;
      pollTimer = setTimeout(tick, pollDelay(attempt));
    };
    tick();
  }

  function startHeartbeat() {
    if (heartbeatTimer) return;
    heartbeatTimer = setInterval(async () => {
      if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
      try {
        await refreshAllSchemes();
        if (!anyModelReady.value && !pollTimer) {
          // 所有模型都被卸载了：门禁重新亮起，恢复快轮询并自动补加载
          const target = pickTargetModel(gateModels.value, gateTarget.value);
          if (target) await activateTarget(gateScheme.value || schemes.value[0] || "", target.name);
          startGatePolling();
        }
      } catch {
        /* 心跳失败忽略，下轮再试 */
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
      await loadModel({ name: modelName }, { silentError: true });
    } catch (error) {
      gateError.value = error?.message || "模型后台加载启动失败，请重试";
    } finally {
      isSwitchingTarget.value = false;
    }
  }

  /** 门禁面板里手动切换方案 */
  async function selectGateScheme(scheme) {
    if (!scheme || scheme === gateScheme.value) return;
    gateScheme.value = scheme;
    gateTarget.value = "";
    const list = await fetchModels(scheme).catch(() => []);
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
    if (booting || bootstrapped.value) {
      startHeartbeat();
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
      await refreshAllSchemes();
      bootstrapped.value = true;
      if (anyModelReady.value) {
        startHeartbeat();
        return;
      }
      const target = pickTargetModel(gateModels.value, restored.model);
      if (target) {
        await activateTarget(gateScheme.value, target.name);
      }
      startGatePolling();
      startHeartbeat();
    } catch {
      bootstrapped.value = true;
      startGatePolling();
      startHeartbeat();
    } finally {
      booting = false;
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
    bootstrap,
    selectGateScheme,
    selectGateModel,
    retryGateLoad,
  };
});
