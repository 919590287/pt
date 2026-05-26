<!-- MapLayout -->
<template>
  <div class="MapLayout">
    <MHeader></MHeader>
    <div id="mapRoot" role="region" aria-label="公交数字孪生地图"></div>
    <RouterView></RouterView>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, provide, shallowRef } from "vue";
import MHeader from "./MHeader.vue";
import { MyMap, MapLayer, DEFAULT_MAP_LAYER_STYLE, CityBuildingsLayer } from "@/mymap/index.js";

defineOptions({
  name: "MapLayout",
});

const MapRef = shallowRef(null);
provide("MapRef", MapRef);

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

  const buildingLayerConfig = window.CITY_BUILDINGS_LAYER || {};
  if (buildingLayerConfig.enabled !== false) {
    const _CityBuildingsLayer = new CityBuildingsLayer({
      ...buildingLayerConfig,
      zIndex: buildingLayerConfig.zIndex ?? 8,
    });
    MapRef.value.addLayer(_CityBuildingsLayer);
  }
});

onBeforeUnmount(() => {
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
