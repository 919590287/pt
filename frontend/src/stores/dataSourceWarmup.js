import { defineStore } from "pinia";
import { computed, ref, shallowRef } from "vue";
import { useModelSelectionStore } from "@/stores/modelSelection.js";
import {
  DEFAULT_REAL_AREA,
  warmRealPassengerFlow,
} from "@/utils/realPassengerFlow.js";

/** 仿真模型由 modelRuntime 预热；本 store 同步准备真实模式完整首屏。 */
export const useDataSourceWarmupStore = defineStore("dataSourceWarmup", () => {
  const selectionStore = useModelSelectionStore();
  const status = ref("idle");
  const error = ref("");
  const result = shallowRef(null);
  let warmupPromise = null;

  const ready = computed(() => status.value === "ready");

  async function warm(options = {}) {
    if (!options.force && ready.value) return result.value;
    if (warmupPromise) return warmupPromise;
    status.value = "loading";
    error.value = "";
    const selection = selectionStore.getSelection("datavisualization");
    const preferredDate = selection.realServiceDate === "average"
      ? ""
      : String(selection.realServiceDate || "");
    const request = warmRealPassengerFlow(DEFAULT_REAL_AREA, preferredDate)
      .then((payload) => {
        result.value = payload;
        status.value = "ready";
        return payload;
      })
      .catch((cause) => {
        status.value = "error";
        error.value = cause?.message || "真实数据预热失败";
        throw cause;
      })
      .finally(() => {
        if (warmupPromise === request) warmupPromise = null;
      });
    warmupPromise = request;
    return request;
  }

  return { status, error, result, ready, warm };
});
