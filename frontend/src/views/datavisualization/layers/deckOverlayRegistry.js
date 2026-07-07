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

// 注册表清空时移除 overlay 控件并释放其 WebGL 资源；下次再 set 时由 ensureState 重建
function teardownState(mapWrapper, state) {
  overlayRegistry.delete(mapWrapper);
  try {
    state.overlay.setProps({ layers: [] });
    mapWrapper?.map?.removeControl(state.overlay);
  } catch (error) {
    void error; // 地图已销毁等场景下静默
  }
}

export function setSharedDeckLayer(mapWrapper, key, layer, order = 0) {
  if (!layer) {
    // 删除路径不经过 ensureState：对未注册的 map/key 删除不应凭空创建 overlay
    const state = overlayRegistry.get(mapWrapper);
    // 删除未注册的 key 时短路：避免高频事件路径（如低 zoom 下的标签移除）触发无效的全量排序+setProps
    if (!state || !state.layers.delete(key)) return true;
    if (state.layers.size === 0) {
      teardownState(mapWrapper, state);
      return true;
    }
    applyLayers(state);
    return true;
  }
  const state = ensureState(mapWrapper);
  if (!state) return false;
  const previous = state.layers.get(key);
  state.layers.set(key, {
    layer,
    order: Number.isFinite(Number(order)) ? Number(order) : 0,
    sequence: previous?.sequence ?? state.nextSequence++,
  });
  applyLayers(state);
  return true;
}

function applyLayers(state) {
  const orderedLayers = [...state.layers.values()]
    .sort((left, right) => left.order - right.order || left.sequence - right.sequence)
    .map((item) => item.layer);
  state.overlay.setProps({ layers: normalizeLayerList(orderedLayers) });
}

export function removeSharedDeckLayer(mapWrapper, key) {
  return setSharedDeckLayer(mapWrapper, key, null);
}
