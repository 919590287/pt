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
    nextSequence: 0,
  };
  overlayRegistry.set(mapWrapper, state);
  return state;
}

export function setSharedDeckLayer(mapWrapper, key, layer, order = 0) {
  const state = ensureState(mapWrapper);
  if (!state) return false;
  if (layer) {
    const previous = state.layers.get(key);
    state.layers.set(key, {
      layer,
      order: Number.isFinite(Number(order)) ? Number(order) : 0,
      sequence: previous?.sequence ?? state.nextSequence++,
    });
  } else {
    state.layers.delete(key);
  }
  const orderedLayers = [...state.layers.values()]
    .sort((left, right) => left.order - right.order || left.sequence - right.sequence)
    .map((item) => item.layer);
  state.overlay.setProps({ layers: normalizeLayerList(orderedLayers) });
  return true;
}

export function removeSharedDeckLayer(mapWrapper, key) {
  return setSharedDeckLayer(mapWrapper, key, null);
}
