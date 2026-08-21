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
    // 页面组失活时按 key 前缀挂起（见 setSharedDeckLayersHidden）：注册项保留，
    // 只是不提交给 overlay。恢复时无需重建 deck layer，切页面是即时的。
    hiddenPrefixes: new Set(),
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

function isHidden(state, key) {
  if (!state.hiddenPrefixes.size) return false;
  for (const prefix of state.hiddenPrefixes) {
    if (key.startsWith(prefix)) return true;
  }
  return false;
}

function commitState(state) {
  // 注册表为空才拆 overlay。仅仅是全部被挂起时保留控件，
  // 否则每次切页面都要重建 MapboxOverlay 及其 WebGL 资源，切换会明显发顿。
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
  const orderedLayers = [...state.layers.entries()]
    .filter(([key]) => !isHidden(state, key))
    .sort(([, left], [, right]) => left.order - right.order || left.sequence - right.sequence)
    .map(([, item]) => item.layer);
  state.overlay.setProps({ layers: normalizeLayerList(orderedLayers) });
}

/**
 * 按 key 前缀挂起/恢复共享 deck 图层，供 MapLayout 在页面组切换时调用。
 *
 * 为什么必须单独走这条路：deck 的 interleaved 图层是 maplibre 的 custom layer，
 * 而 maplibre 的 Style#serialize 明确跳过 custom layer（源码原注释：
 * "this check will skip all custom layers"）。所以 MapLayout 里按
 * `map.getStyle().layers` 遍历 + setLayoutProperty 隐藏的那套，对 deck 图层完全无效——
 * 离开运行监测后客流流向的 OD 线、人口栅格、客流走廊等会一直留在共享地图上。
 *
 * 挂起只是不提交给 overlay，deck layer 实例和 GPU 资源都保留，恢复是即时的。
 */
export function setSharedDeckLayersHidden(mapWrapper, prefixes, hidden) {
  const state = overlayRegistry.get(mapWrapper);
  if (!state || !prefixes?.length) return false;
  let changed = false;
  for (const prefix of prefixes) {
    if (hidden) {
      if (!state.hiddenPrefixes.has(prefix)) {
        state.hiddenPrefixes.add(prefix);
        changed = true;
      }
    } else if (state.hiddenPrefixes.delete(prefix)) {
      changed = true;
    }
  }
  if (changed) scheduleCommit(state);
  return changed;
}

export function removeSharedDeckLayer(mapWrapper, key) {
  return setSharedDeckLayer(mapWrapper, key, null);
}
