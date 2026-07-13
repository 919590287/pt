import { MapboxOverlay } from "@deck.gl/mapbox";

const overlayRegistry = new WeakMap();
let batchDepth = 0;
const batchedStates = new Set();

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
    mapWrapper,
    overlay,
    layers: new Map(),
    nextSequence: 0,
  };
  overlayRegistry.set(mapWrapper, state);
  return state;
}

// 注册表清空时移除 overlay 控件并释放其 WebGL 资源；下次再 set 时由 ensureState 重建
function teardownState(mapWrapper, state) {
  batchedStates.delete(state);
  overlayRegistry.delete(mapWrapper);
  try {
    state.overlay.setProps({ layers: [] });
    mapWrapper?.map?.removeControl(state.overlay);
  } catch (error) {
    void error; // 地图已销毁等场景下静默
  }
}

function commitState(state) {
  if (!state.layers.size) {
    teardownState(state.mapWrapper, state);
    return;
  }
  applyLayers(state);
}

function scheduleCommit(state) {
  if (batchDepth > 0) {
    batchedStates.add(state);
    return;
  }
  commitState(state);
}

// 多个业务图层经常在同一缩放帧内一起更新。批处理只合并注册表排序与
// MapboxOverlay.setProps 提交，不改变任何 deck layer 的数据、样式或顺序。
export function batchSharedDeckLayerUpdates(callback) {
  batchDepth += 1;
  try {
    return callback();
  } finally {
    batchDepth -= 1;
    if (batchDepth === 0 && batchedStates.size) {
      const states = [...batchedStates];
      batchedStates.clear();
      states.forEach(commitState);
    }
  }
}

export function setSharedDeckLayer(mapWrapper, key, layer, order = 0) {
  if (!layer) {
    // 删除路径不经过 ensureState：对未注册的 map/key 删除不应凭空创建 overlay
    const state = overlayRegistry.get(mapWrapper);
    // 删除未注册的 key 时短路：避免高频事件路径（如低 zoom 下的标签移除）触发无效的全量排序+setProps
    if (!state || !state.layers.delete(key)) return true;
    scheduleCommit(state);
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
  scheduleCommit(state);
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
