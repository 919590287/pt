<!-- MapLayout -->
<template>
  <div class="MapLayout">
    <MHeader></MHeader>
    <div id="mapRoot" role="region" aria-label="公交数字孪生地图"></div>
    <!-- 依赖模型的页面等目标模型就绪；数据管理始终可挂载。 -->
    <RouterView v-if="route.meta?.requiresModel === false || !modelRuntime.gateVisible" v-slot="{ Component }">
      <KeepAlive :include="CACHED_PAGE_COMPONENTS">
        <component :is="Component" />
      </KeepAlive>
    </RouterView>
    <ModelLoadGate />
  </div>
</template>

<script setup>
import "maplibre-gl/dist/maplibre-gl.css";
import { onBeforeUnmount, onMounted, provide, shallowRef, watch } from "vue";
import { useRoute } from "vue-router";
import MHeader from "./MHeader.vue";
import ModelLoadGate from "@/components/ModelLoadGate.vue";
import { useModelRuntimeStore } from "@/stores/modelRuntime.js";
import { useDisplayRangeStore, DISPLAY_RANGE_ALL } from "@/stores/displayRange.js";
import { MyMap, MapLayer, DEFAULT_MAP_LAYER_STYLE, CityBuildingsLayer, lngLatToWebMercator } from "@/mymap/index.js";
import { getCachedAdminDistricts, warmRealData } from "@/utils/realDataCache.js";
import { warmRealPassengerFlow } from "@/utils/realPassengerFlow.js";
import { activeDistrictContext, normalizeAdminDistrictCollection } from "@/utils/adminDistrictRange.js";
import { quarantineInactiveStyleLayers } from "@/utils/mapLayerOwnership.js";

defineOptions({
  name: "MapLayout",
});

// keep-alive 的高频页面：切换只做 deactivate，不销毁图层/worker/数据管线。
// 运行监测与客流分析是同一组件（DataVisualization）跨两条路由复用同一实例，归入同一页面组 rm。
const CACHED_PAGE_COMPONENTS = ["DataManagement", "DataVisualization", "TransferAnalysis"];
// stylePrefixes：各页面（组）在共享 maplibre 地图上创建的样式图层 id 前缀，
// 失活时按前缀记录可见性并隐藏，激活时恢复。rm 组包含 XLZL/ZDZL 子组件的历史无前缀 id。
const PAGE_GROUPS = {
  datamanagement: { key: "dm", stylePrefixes: ["dm-"] },
  datavisualization: {
    key: "rm",
    stylePrefixes: ["rm-", "pfa-", "selected-station-ring", "station-reachability-", "station-od-curve", "selected-route-stops"],
  },
  passengerflowanalysis: {
    key: "rm",
    stylePrefixes: ["rm-", "pfa-", "selected-station-ring", "station-reachability-", "station-od-curve", "selected-route-stops"],
  },
  transferanalysis: { key: "ta", stylePrefixes: ["ta-"] },
};

const MapRef = shallowRef(null);
provide("MapRef", MapRef);

const modelRuntime = useModelRuntimeStore();
const displayRangeStore = useDisplayRangeStore();
const route = useRoute();

// 共享地图上属于 MapLayout 自己的常驻图层（底图瓦片、建筑）；其余图层都归当前激活页面组所有
const baseLayerIds = new Set();
// 页面组失活时：MyMap 自定义图层摘下暂存（不 dispose，GPU 资源保留）；
// maplibre 样式图层按页面前缀记录当前可见性后统一隐藏，激活时按记录恢复。
const pageLayerStash = new Map();
const styleVisibilityStash = new Map();
let styleOwnershipReconcileFrame = null;
let districtCameraSeq = 0;

async function focusSharedDisplayRange() {
  const selected = displayRangeStore.selected;
  if (!MapRef.value || !selected || selected === DISPLAY_RANGE_ALL) return false;
  const seq = ++districtCameraSeq;
  try {
    const data = await getCachedAdminDistricts("广州市");
    if (seq !== districtCameraSeq || displayRangeStore.selected !== selected || !MapRef.value) return false;
    const collection = normalizeAdminDistrictCollection(data?.collection);
    const context = activeDistrictContext(collection, selected, DISPLAY_RANGE_ALL);
    const bounds = context?.bounds;
    if (!Array.isArray(bounds) || bounds.length < 4) return false;
    const points = [
      lngLatToWebMercator(bounds[0], bounds[1]),
      lngLatToWebMercator(bounds[2], bounds[3]),
    ].filter((point) => Array.isArray(point) && point.every(Number.isFinite));
    if (points.length < 2) return false;
    MapRef.value.setFitZoomAndCenterByPoints?.(points);
    return true;
  } catch {
    return false;
  }
}

function activePageGroupKey() {
  return PAGE_GROUPS[route.name]?.key || "";
}

function stashOwnedPageLayer(groupKey, layer) {
  if (!groupKey || !layer) return;
  const stashed = pageLayerStash.get(groupKey) || [];
  if (!stashed.includes(layer)) stashed.push(layer);
  pageLayerStash.set(groupKey, stashed);
}

// 自定义 Three/Deck 图层必须显式携带页面归属。异步回调即使在失活后才完成，
// 也只会进入原页面的暂存区，不会被当前页面误收编。
function addOwnedPageLayer(groupKey, layer) {
  const map = MapRef.value;
  if (!map || !layer || layer.isDisposed) return;
  layer.pageGroupKey = groupKey;
  if (activePageGroupKey() === groupKey) {
    map.addLayer(layer);
  } else {
    stashOwnedPageLayer(groupKey, layer);
  }
}

provide("PageMapLayerHost", {
  addLayer: addOwnedPageLayer,
});

function reconcileStyleLayerOwnership() {
  styleOwnershipReconcileFrame = null;
  quarantineInactiveStyleLayers(
    MapRef.value?.map,
    PAGE_GROUPS,
    activePageGroupKey(),
    styleVisibilityStash,
  );
}

function scheduleStyleLayerOwnershipReconcile() {
  if (styleOwnershipReconcileFrame != null) return;
  if (typeof requestAnimationFrame === "function") {
    styleOwnershipReconcileFrame = requestAnimationFrame(reconcileStyleLayerOwnership);
  } else {
    styleOwnershipReconcileFrame = true;
    queueMicrotask(reconcileStyleLayerOwnership);
  }
}

function stashPageLayers(groupKey, stylePrefixes) {
  const map = MapRef.value;
  if (!map) return;
  const pageLayers = map.layers.filter((layer) => !baseLayerIds.has(layer.id));
  pageLayers.forEach((layer) => {
    // 未迁移到显式归属 API 的同步建层默认属于离开的页面；已经显式标注的
    // 晚到图层则回到自己的暂存区，避免被错误归到当前页面。
    const ownerKey = layer.pageGroupKey || groupKey;
    layer.pageGroupKey = ownerKey;
    stashOwnedPageLayer(ownerKey, layer);
    map.removeLayer(layer);
  });

  const gl = map.map;
  const styleLayers = gl?.getStyle?.()?.layers || [];
  const visibility = styleVisibilityStash.get(groupKey) || new Map();
  for (const styleLayer of styleLayers) {
    if (!stylePrefixes.some((prefix) => styleLayer.id.startsWith(prefix))) continue;
    visibility.set(styleLayer.id, gl.getLayoutProperty(styleLayer.id, "visibility") || "visible");
    gl.setLayoutProperty(styleLayer.id, "visibility", "none");
  }
  styleVisibilityStash.set(groupKey, visibility);
}

function restorePageLayers(groupKey) {
  const map = MapRef.value;
  if (!map) return;
  const stashed = pageLayerStash.get(groupKey) || [];
  pageLayerStash.delete(groupKey);
  stashed.forEach((layer) => {
    if (!layer.isDisposed) map.addLayer(layer);
  });

  const gl = map.map;
  const visibility = styleVisibilityStash.get(groupKey);
  styleVisibilityStash.delete(groupKey);
  if (!gl || !visibility) return;
  visibility.forEach((value, layerId) => {
    if (gl.getLayer?.(layerId)) gl.setLayoutProperty(layerId, "visibility", value);
  });
}

watch(
  () => route.name,
  (next, prev) => {
    // 无论两个路由是否复用同一页面实例（如运行监测 ↔ 客流分析），
    // 功能切换都要先恢复共享行政区视角。
    focusSharedDisplayRange();
    const prevGroup = PAGE_GROUPS[prev];
    const nextGroup = PAGE_GROUPS[next];
    // 运行监测 ↔ 客流分析：同组同实例，仅 mode prop 变化，不做任何图层挪动
    if (prevGroup?.key === nextGroup?.key) return;
    if (prevGroup) stashPageLayers(prevGroup.key, prevGroup.stylePrefixes);
    if (nextGroup) restorePageLayers(nextGroup.key);
    scheduleStyleLayerOwnershipReconcile();
  },
);

watch(
  () => displayRangeStore.selected,
  () => focusSharedDisplayRange(),
  { flush: "post" },
);

watch(
  () => route.meta?.requiresModel,
  (requiresModel) => {
    if (requiresModel === false) {
      modelRuntime.pauseModelDemand();
    } else {
      modelRuntime.bootstrap();
    }
  },
);

// 门禁重新亮起（模型全部被卸载）时 KeepAlive 子树整体销毁，暂存图层随之失效
watch(
  () => modelRuntime.gateVisible,
  (visible) => {
    if (visible) {
      pageLayerStash.clear();
      styleVisibilityStash.clear();
    }
  },
);

onMounted(() => {
  MapRef.value = new MyMap({
    rootId: "mapRoot",
    center: [12634609, 2659952],
    // center: [12636614, 2642694.2],
    zoom: 10.74,
    openGPUPick: true,
    noControls: false,
    enableRotate: false,
  });

  const _MapLayer = new MapLayer({ tileClass: DEFAULT_MAP_LAYER_STYLE, zIndex: -1 });
  MapRef.value.addLayer(_MapLayer);
  baseLayerIds.add(_MapLayer.id);

  const buildingLayerConfig = window.CITY_BUILDINGS_LAYER || {};
  if (buildingLayerConfig.enabled !== false) {
    const _CityBuildingsLayer = new CityBuildingsLayer({
      ...buildingLayerConfig,
      zIndex: buildingLayerConfig.zIndex ?? 8,
    });
    MapRef.value.addLayer(_CityBuildingsLayer);
    baseLayerIds.add(_CityBuildingsLayer.id);
  }

  warmRealData("广州市");
  warmRealPassengerFlow("广州市").catch(() => null);
  focusSharedDisplayRange();

  if (route.meta?.requiresModel !== false) {
    modelRuntime.bootstrap();
  } else {
    modelRuntime.pauseModelDemand();
  }

  // styledata 会在异步 addLayer / setLayoutProperty 后触发。每个渲染帧只扫描一次，
  // 将非当前页面的晚到图层立即隔离，堵住“静置后串出线网/站点层”的竞态。
  MapRef.value.map?.on?.("styledata", scheduleStyleLayerOwnershipReconcile);
  scheduleStyleLayerOwnershipReconcile();
});

onBeforeUnmount(() => {
  MapRef.value?.map?.off?.("styledata", scheduleStyleLayerOwnershipReconcile);
  if (typeof styleOwnershipReconcileFrame === "number" && typeof cancelAnimationFrame === "function") {
    cancelAnimationFrame(styleOwnershipReconcileFrame);
  }
  styleOwnershipReconcileFrame = null;
  MapRef.value?.dispose?.();
  MapRef.value = null;
});
</script>

<style lang="scss" scoped>
.MapLayout {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  width: 100vw;
  height: 100vh;
  min-width: 0;
  color: var(--app-ink);
  background: var(--app-surface-soft);
  #mapRoot {
    width: 100%;
    height: 100%;
    min-height: 100%;
  }
}
</style>
