import { defineStore } from "pinia";
import { ref } from "vue";

const STORAGE_KEY = "gjcxfzksh:model-selection";

function readSelections() {
  if (typeof sessionStorage === "undefined") return {};
  try {
    const parsed = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || "{}");
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function writeSelections(value) {
  if (typeof sessionStorage === "undefined") return;
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(value || {}));
}

export const useModelSelectionStore = defineStore("modelSelection", () => {
  const selections = ref(readSelections());

  function getSelection(pageKey) {
    const selection = selections.value?.[pageKey] || {};
    return {
      sourceMode: selection.sourceMode === "real" ? "real" : "simulation",
      scheme: selection.scheme || "",
      model: selection.model || "",
      realServiceDate: selection.realServiceDate || "average",
    };
  }

  function setSelection(pageKey, selection = {}) {
    const previous = selections.value?.[pageKey] || {};
    const next = { ...previous, ...selection };
    selections.value = {
      ...selections.value,
      [pageKey]: {
        sourceMode: next.sourceMode === "real" ? "real" : "simulation",
        scheme: next.scheme || "",
        model: next.model || "",
        realServiceDate: next.realServiceDate || "average",
      },
    };
    writeSelections(selections.value);
  }

  function clearSelection(pageKey) {
    const next = { ...selections.value };
    delete next[pageKey];
    selections.value = next;
    writeSelections(next);
  }

  return {
    selections,
    getSelection,
    setSelection,
    clearSelection,
  };
});
