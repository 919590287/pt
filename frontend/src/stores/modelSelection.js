import { defineStore } from "pinia";
import { ref } from "vue";

const STORAGE_KEY = "gjcxfzksh:model-selection";

function normalizeViewState(value) {
  if (!value || typeof value !== "object") return {};
  return Object.fromEntries(
    Object.entries(value).filter(([, item]) => typeof item === "string"),
  );
}

function normalizeMapCamera(value) {
  const center = Array.isArray(value?.center) ? value.center.map(Number) : [];
  const zoom = Number(value?.zoom);
  const pitch = Number(value?.pitch);
  const rotation = Number(value?.rotation);
  if (center.length !== 2 || !center.every(Number.isFinite) || !Number.isFinite(zoom)) return null;
  return {
    center,
    zoom,
    pitch: Number.isFinite(pitch) ? pitch : 90,
    rotation: Number.isFinite(rotation) ? rotation : 0,
  };
}

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
      realServiceDate: selection.realServiceDate || "",
      viewState: normalizeViewState(selection.viewState),
      mapCamera: normalizeMapCamera(selection.mapCamera),
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
        realServiceDate: next.realServiceDate || "",
        viewState: normalizeViewState(next.viewState),
        mapCamera: normalizeMapCamera(next.mapCamera),
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
