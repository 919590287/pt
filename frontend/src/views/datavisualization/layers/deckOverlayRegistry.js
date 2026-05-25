import { MapboxOverlay } from "@deck.gl/mapbox";

const overlayRegistry = new WeakMap();

function normalizeLayerList(layers) {
  const result = [];
  for (const layer of layers) {
    if (Array.isArray(layer)) {
      result.push(...normalizeLayerList(layer));
    } else if (layer) {
      result.push(layer);
    }
  }
  return result;
}

function ensureState(mapWrapper) {
  if (!mapWrapper?.map) return null;
  let state = overlayRegistry.get(mapWrapper);
  if (state) return state;

  const overlay = new MapboxOverlay({
    interleaved: true,
    layers: [],
  });
  mapWrapper.map.addControl(overlay);
  state = {
    overlay,
    layers: new Map(),
  };
  overlayRegistry.set(mapWrapper, state);
  return state;
}

export function setSharedDeckLayer(mapWrapper, key, layer) {
  const state = ensureState(mapWrapper);
  if (!state) return false;
  if (layer) {
    state.layers.set(key, layer);
  } else {
    state.layers.delete(key);
  }
  state.overlay.setProps({ layers: normalizeLayerList([...state.layers.values()]) });
  return true;
}

export function removeSharedDeckLayer(mapWrapper, key) {
  return setSharedDeckLayer(mapWrapper, key, null);
}
