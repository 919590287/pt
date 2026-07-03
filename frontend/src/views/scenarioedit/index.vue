<!-- 线网优化（v2 重构版）：圈区域 → 编辑线网 → 生成基线+方案双模型并运行 -->
<template>
  <div class="netopt-wrapper">
    <!-- 右上角：母本模型选择（仅仿真模型） -->
    <ModelPickerBar />

    <!-- 左侧操作面板 -->
    <div class="panel left-panel" :class="{ collapsed: leftCollapsed }">
      <div class="panel-header">
        <div class="header-title">
          <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
          </svg>
          <span>线网优化</span>
        </div>
        <!-- 草稿切换 -->
        <el-dropdown v-if="store.parentReady" trigger="click" @command="handleDraftCommand">
          <span class="draft-chip">{{ store.draft.name }}<el-icon class="ml2"><ArrowDown /></el-icon></span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="__new">＋ 新建草稿</el-dropdown-item>
              <el-dropdown-item command="__rename">重命名当前草稿</el-dropdown-item>
              <el-dropdown-item v-if="store.draft.draftId" command="__copy">复制当前草稿</el-dropdown-item>
              <el-dropdown-item v-if="store.draft.draftId" command="__delete" divided>删除当前草稿</el-dropdown-item>
              <el-dropdown-item v-for="d in store.draftList" :key="d.draftId" :command="d.draftId" divided>
                {{ d.name }}（{{ (d.edits || []).length }}项修改）
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>

      <el-scrollbar class="panel-body">
        <div v-if="!store.parentReady" class="guide-block">
          <div class="guide-icon">🛰</div>
          <p><b>请先在右上角选择母本仿真模型</b></p>
          <p class="sub">线网优化以一个已运行的仿真模型为底，圈定研究区域后对公交线网做修改，一键生成"基线 + 方案"两个对比模型。</p>
        </div>

        <template v-else>
          <!-- ① 研究区域 -->
          <section class="step-card">
            <div class="step-title"><span class="num">①</span> 圈定研究区域</div>
            <AreaPanel />
          </section>

          <!-- ② 线网编辑 -->
          <section class="step-card" :class="{ disabled: !hasArea }">
            <div class="step-title">
              <span class="num">②</span> 线网编辑
              <span v-if="!hasArea" class="lock-tip">（先圈定区域）</span>
              <span v-else-if="store.activeTool" class="tool-tip">当前：{{ toolLabel }}（ESC 退出）</span>
            </div>
            <EditToolbox v-if="hasArea" />
          </section>

          <!-- ③ 生成 -->
          <section class="step-card">
            <div class="step-title"><span class="num">③</span> 生成仿真模型</div>
            <p class="gen-hint">在右侧面板核对修改清单后，点击「生成仿真模型」。</p>
          </section>
        </template>
      </el-scrollbar>
    </div>
    <button class="collapse-tab left" @click="leftCollapsed = !leftCollapsed">{{ leftCollapsed ? "»" : "«" }}</button>

    <!-- 右侧方案面板 -->
    <div class="panel right-panel" :class="{ collapsed: rightCollapsed }">
      <div class="panel-header">
        <div class="header-title">
          <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
            <path d="M18.5 2.5a2.121 2.121 0 1 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
          </svg>
          <span>方案面板</span>
        </div>
      </div>
      <el-scrollbar class="panel-body">
        <ModList @hover-edit="handleHoverEdit" />
        <button class="generate-btn" :disabled="!canGenerate" @click="wizardVisible = true">
          <span>生成仿真模型</span>
          <span v-if="store.editCount" class="count">{{ store.editCount }} 项修改</span>
        </button>
        <p v-if="!canGenerate" class="gen-block-tip">{{ generateBlockReason }}</p>
        <div class="divider"></div>
        <RunTaskList />
      </el-scrollbar>
    </div>
    <button class="collapse-tab right" @click="rightCollapsed = !rightCollapsed">{{ rightCollapsed ? "«" : "»" }}</button>

    <!-- 线路点选候选弹层 -->
    <div v-if="routePicker.visible" class="route-picker" :style="{ left: routePicker.x + 'px', top: routePicker.y + 'px' }">
      <div class="picker-title">选择线路</div>
      <button v-for="c in routePicker.candidates" :key="`${c.lineId}||${c.routeId}`" class="picker-item" @click="pickCandidate(c)">
        {{ c.lineName }} <span class="dir">{{ c.routeId }}</span>
      </button>
      <button class="picker-cancel" @click="routePicker.visible = false">取消</button>
    </div>

    <GenerateWizard v-model="wizardVisible" />
  </div>
</template>

<script setup>
import { computed, inject, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { ArrowDown } from "@element-plus/icons-vue";
import { useScenarioEditStore } from "./store";
import { useMapTools } from "./composables/useMapTools";
import ModelPickerBar from "./components/ModelPickerBar.vue";
import AreaPanel from "./components/AreaPanel.vue";
import EditToolbox from "./components/EditToolbox.vue";
import ModList from "./components/ModList.vue";
import GenerateWizard from "./components/GenerateWizard.vue";
import RunTaskList from "./components/RunTaskList.vue";
import {
  updateBaseNetwork, updateArea, approxBufferRing, buildOverlayFeatures, updateOverlay,
  updateHighlight, removeAllEditorLayers,
} from "./layers/editorLayers";

const MapRef = inject("MapRef");
const store = useScenarioEditStore();

const leftCollapsed = ref(false);
const rightCollapsed = ref(false);
const wizardVisible = ref(false);

const hasArea = computed(() => Boolean(store.draft.area?.polygon?.length >= 3));

const canGenerate = computed(() => store.parentReady && hasArea.value && store.editCount > 0);
const generateBlockReason = computed(() => {
  if (!store.parentReady) return "请先选择并加载母本模型";
  if (!hasArea.value) return "请先圈定研究区域";
  if (!store.editCount) return "请至少添加一项线网修改";
  return "";
});

const TOOL_LABELS = {
  "area.draw": "绘制研究区域",
  "pick.line": "点选线路",
  "pick.stop": "点选站点",
  "pick.link": "点选路段",
  "draw.route": "绘制线路走向",
  "draw.link": "绘制新路段",
  "place.stop": "放置站点",
};
const toolLabel = computed(() => TOOL_LABELS[store.activeTool] || "");

// ---------------- 线路候选弹层 ----------------
const routePicker = reactive({ visible: false, candidates: [], x: 0, y: 0 });

function onPickRouteCandidates(candidates, point) {
  routePicker.candidates = candidates;
  routePicker.x = Math.min(point.x, window.innerWidth - 240);
  routePicker.y = Math.min(point.y + 60, window.innerHeight - 200);
  routePicker.visible = true;
}

function pickCandidate(c) {
  store.selectRoute(c.lineId, c.routeId);
  routePicker.visible = false;
}

useMapTools({ MapRef, store, onPickRouteCandidates });

// ---------------- 地图渲染联动 ----------------
const map = () => MapRef.value?.map || null;

function renderBaseNetwork() {
  const m = map();
  if (!m || !store.lines.length) return;
  const routeFeatures = [];
  for (const r of store.routeIndex.values()) {
    if (!r.geometry || r.geometry.length < 2) continue;
    routeFeatures.push({
      type: "Feature",
      geometry: { type: "LineString", coordinates: r.geometry },
      properties: { lineId: r.lineId, lineName: r.lineName, routeId: r.routeId, mode: r.mode || "bus" },
    });
  }
  const stopFeatures = [];
  for (const s of store.stopIndex.values()) {
    stopFeatures.push({
      type: "Feature",
      geometry: { type: "Point", coordinates: [s.lng, s.lat] },
      properties: { stopId: s.id, name: s.name },
    });
  }
  updateBaseNetwork(m, routeFeatures, stopFeatures);
}

function renderArea() {
  const m = map();
  if (!m) return;
  const polygon = store.draft.area?.polygon || null;
  const buffer = polygon ? approxBufferRing(polygon, store.draft.area?.bufferM || 0) : null;
  updateArea(m, polygon, buffer);
}

function renderOverlay() {
  const m = map();
  if (!m) return;
  updateOverlay(m, buildOverlayFeatures(store.draft.edits, store.routeIndex, store.stopIndex));
}

function renderHighlight() {
  const m = map();
  if (!m) return;
  const route = store.selectedRoute;
  const stop = store.selectedStop;
  updateHighlight(m, route?.geometry || null, stop ? [stop.lng, stop.lat] : null);
}

function handleHoverEdit(edit) {
  const m = map();
  if (!m) return;
  if (!edit) {
    renderHighlight();
    return;
  }
  // 悬停修改项时借用高亮层展示其影响范围
  const features = buildOverlayFeatures([edit], store.routeIndex, store.stopIndex);
  const line = features.find((f) => f.geometry.type === "LineString");
  const point = features.find((f) => f.geometry.type === "Point");
  updateHighlight(m, line?.geometry.coordinates || null, point?.geometry.coordinates || null);
}

let stopWatchers = [];

function setupWatchers() {
  stopWatchers.push(watch(() => store.lines, renderBaseNetwork, { deep: false }));
  stopWatchers.push(watch(() => [store.draft.area?.polygon, store.draft.area?.bufferM], renderArea, { deep: true }));
  stopWatchers.push(watch(() => store.draft.edits, renderOverlay, { deep: true }));
  stopWatchers.push(watch(() => [store.selection.lineId, store.selection.routeId, store.selection.stopId], renderHighlight));
}

// 地图可能晚于组件就绪
function whenMapReady(fn) {
  if (map()) {
    fn();
    return;
  }
  const timer = setInterval(() => {
    if (map()) {
      clearInterval(timer);
      fn();
    }
  }, 300);
  stopWatchers.push(() => clearInterval(timer));
}

// ---------------- 草稿操作 ----------------
async function handleDraftCommand(cmd) {
  if (cmd === "__new") {
    await store.newDraft();
    ElMessage.success("已新建空白草稿");
    return;
  }
  if (cmd === "__rename") {
    try {
      const { value } = await ElMessageBox.prompt("草稿名称", "重命名", { inputValue: store.draft.name });
      if (value?.trim()) store.draft.name = value.trim();
    } catch { /* cancel */ }
    return;
  }
  if (cmd === "__copy") {
    await store.copyDraft(store.draft.draftId, `${store.draft.name}-副本`);
    ElMessage.success("已复制为新草稿");
    return;
  }
  if (cmd === "__delete") {
    try {
      await ElMessageBox.confirm("删除当前草稿及其全部修改项？", "删除草稿", { type: "warning" });
      await store.deleteDraft(store.draft.draftId);
      ElMessage.success("草稿已删除");
    } catch { /* cancel */ }
    return;
  }
  const target = store.draftList.find((d) => d.draftId === cmd);
  if (target) {
    store.openDraft(target);
    ElMessage.success(`已切换到草稿「${target.name}」`);
  }
}

// ---------------- 生命周期 ----------------
onMounted(() => {
  setupWatchers();
  whenMapReady(() => {
    renderBaseNetwork();
    renderArea();
    renderOverlay();
  });
  store.startJobPolling();
});

onUnmounted(() => {
  stopWatchers.forEach((s) => (typeof s === "function" ? s() : s?.stop?.()));
  stopWatchers = [];
  store.stopJobPolling();
  store.setTool("");
  const m = map();
  if (m) removeAllEditorLayers(m);
});
</script>

<style lang="scss" scoped>
.netopt-wrapper {
  position: relative;
  width: 100%;
  height: 100%;
  pointer-events: none; // 面板自身恢复事件，让地图可交互

  > * {
    pointer-events: auto;
  }
}

.panel {
  position: fixed;
  top: calc(64px * var(--app-layout-scale, 1) + 8px);
  bottom: 16px;
  z-index: var(--z-panel, 30);
  display: flex;
  flex-direction: column;
  width: min(400px, 32vw);
  background: var(--app-panel-bg, #fff);
  border: 1px solid var(--app-border, #dde3ec);
  border-radius: var(--app-panel-radius, 14px);
  box-shadow: var(--app-shadow-panel, 0 8px 30px rgba(15, 35, 72, 0.12));
  overflow: hidden;
  transition: transform 0.25s ease;

  &.left-panel {
    left: 16px;

    &.collapsed {
      transform: translateX(calc(-100% - 24px));
    }
  }

  &.right-panel {
    right: 16px;
    width: min(360px, 30vw);

    &.collapsed {
      transform: translateX(calc(100% + 24px));
    }
  }
}

.collapse-tab {
  position: fixed;
  top: 50%;
  z-index: calc(var(--z-panel, 30) + 1);
  width: 20px;
  height: 52px;
  border: 1px solid var(--app-border, #dde3ec);
  background: var(--app-panel-bg, #fff);
  color: var(--app-blue, #1569de);
  cursor: pointer;
  font-size: 12px;

  &.left {
    left: 0;
    border-radius: 0 8px 8px 0;
  }

  &.right {
    right: 0;
    border-radius: 8px 0 0 8px;
  }
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 44px;
  padding: 6px 14px;
  background: rgba(21, 105, 222, 0.07);
  border-bottom: 1px solid rgba(21, 105, 222, 0.15);
  color: var(--app-blue, #1569de);

  .header-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 15px;
    font-weight: 750;

    .icon {
      width: 17px;
      height: 17px;
    }
  }

  .draft-chip {
    display: inline-flex;
    align-items: center;
    max-width: 170px;
    padding: 3px 10px;
    font-size: 12px;
    font-weight: 600;
    color: var(--app-blue, #1569de);
    background: #fff;
    border: 1px solid rgba(21, 105, 222, 0.3);
    border-radius: 999px;
    cursor: pointer;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;

    .ml2 { margin-left: 3px; }
  }
}

.panel-body {
  flex: 1;

  :deep(.el-scrollbar__view) {
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
}

.guide-block {
  text-align: center;
  padding: 36px 16px;
  color: var(--app-ink-weak, #64748b);

  .guide-icon {
    font-size: 34px;
    margin-bottom: 10px;
  }

  p {
    margin: 4px 0;
    font-size: 13px;
  }

  .sub {
    font-size: 12px;
    line-height: 1.7;
  }
}

.step-card {
  border: 1px solid var(--app-border, #e8edf5);
  border-radius: 12px;
  padding: 10px 12px;
  background: #fff;

  &.disabled {
    opacity: 0.55;
    pointer-events: none;
  }

  .step-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    font-weight: 750;
    margin-bottom: 10px;

    .num {
      color: var(--app-blue, #1569de);
    }

    .lock-tip {
      font-size: 11px;
      font-weight: 400;
      color: #94a3b8;
    }

    .tool-tip {
      font-size: 11px;
      font-weight: 600;
      color: #0f9f6e;
      margin-left: auto;
    }
  }

  .gen-hint {
    margin: 0;
    font-size: 12px;
    color: var(--app-ink-weak, #6b7789);
  }
}

.generate-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  padding: 12px 0;
  border: none;
  border-radius: 12px;
  background: linear-gradient(135deg, #1569de, #0b91b7);
  color: #fff;
  font-size: 15px;
  font-weight: 750;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(21, 105, 222, 0.35);

  &:disabled {
    background: #cbd5e1;
    box-shadow: none;
    cursor: not-allowed;
  }

  .count {
    font-size: 11px;
    font-weight: 600;
    background: rgba(255, 255, 255, 0.25);
    border-radius: 999px;
    padding: 2px 8px;
  }
}

.gen-block-tip {
  margin: 0;
  font-size: 11px;
  color: #94a3b8;
  text-align: center;
}

.divider {
  height: 1px;
  background: var(--app-border, #e8edf5);
}

.route-picker {
  position: fixed;
  z-index: 3000;
  min-width: 200px;
  background: #fff;
  border: 1px solid var(--app-border, #dde3ec);
  border-radius: 10px;
  box-shadow: 0 10px 32px rgba(15, 35, 72, 0.18);
  padding: 6px;

  .picker-title {
    font-size: 11px;
    color: #94a3b8;
    padding: 4px 8px;
  }

  .picker-item {
    display: block;
    width: 100%;
    text-align: left;
    padding: 6px 8px;
    font-size: 13px;
    border: none;
    background: transparent;
    border-radius: 6px;
    cursor: pointer;

    &:hover {
      background: rgba(21, 105, 222, 0.08);
    }

    .dir {
      font-size: 11px;
      color: #94a3b8;
      margin-left: 6px;
    }
  }

  .picker-cancel {
    display: block;
    width: 100%;
    padding: 5px 8px;
    margin-top: 2px;
    font-size: 12px;
    color: #64748b;
    border: none;
    border-top: 1px solid var(--app-border, #eef2f7);
    background: transparent;
    cursor: pointer;
  }
}

@media (max-width: 900px) {
  .panel {
    width: min(360px, 86vw);

    &.right-panel {
      width: min(340px, 86vw);
    }
  }
}
</style>
