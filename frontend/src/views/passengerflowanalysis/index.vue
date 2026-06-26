<!-- 客流分析：复用「运行监测」视图（mode=pfa），仅保留线路 / 站点两项，右侧用 5 段客流面板 -->
<template>
  <RunMonitorView mode="pfa" />
</template>

<script setup>
import RunMonitorView from "@/views/datavisualization/index.vue";
</script>
