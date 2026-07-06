<template>
  <div class="datebase_box model-picker-bar" role="search" aria-label="母本仿真模型选择">
    <label class="handle" for="scenarioedit-scheme-selector">母本仿真模型</label>
    <el-select
      id="scenarioedit-scheme-selector"
      v-model="scheme"
      class="scheme-select"
      placeholder="选择区域方案"
      :loading="loadingSchemes"
      size="default"
      @change="handleSchemeChange"
    >
      <el-option v-for="item in schemeList" :key="item" :label="item" :value="item" />
    </el-select>
    <el-select
      v-model="modelName"
      class="model-select"
      placeholder="选择仿真模型"
      :disabled="!scheme"
      :loading="loadingModels"
      clearable
      filterable
      size="default"
      @change="handleModelChange"
      aria-label="选择母本仿真模型"
    >
      <el-option
        v-for="item in modelList"
        :key="item.name"
        :label="item.displayName || item.name"
        :value="item.name"
        :disabled="!item.cuttable"
      >
        <div class="model-option">
          <span class="opt-name">{{ item.displayName || item.name }}</span>
          <span class="opt-tags">
            <el-tag size="small" :type="item.scopeLabel === '公共' ? 'info' : 'warning'" effect="plain">{{ item.scopeLabel }}</el-tag>
            <el-tag v-if="item.optimization" size="small" type="success" effect="plain">优化产物</el-tag>
            <el-tag v-if="!item.cuttable" size="small" type="danger" effect="plain">缺plans不可切分</el-tag>
            <el-tag v-else-if="item.loadStatus" size="small" type="success" effect="plain">已加载</el-tag>
          </span>
        </div>
      </el-option>
    </el-select>
    <span v-if="statusText" class="bar-status" :class="{ ok: store.parentReady }" role="status">{{ statusText }}</span>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, computed } from "vue";
import { ElMessage } from "element-plus";
import { getSchemeList, getModelList, loadModel } from "@/api/scheme";
import { useModelSelectionStore } from "@/stores/modelSelection";
import { useScenarioEditStore } from "../store";

const PAGE_KEY = "scenarioedit";
// 运行监测 / 客流分析共用的“当前模型”选择键：线网优化默认以它为母本，无需再次选择
const CURRENT_MODEL_KEY = "datavisualization";

const store = useScenarioEditStore();
const selectionStore = useModelSelectionStore();

const scheme = ref("");
const modelName = ref("");
const schemeList = ref([]);
const modelList = ref([]);
const loadingSchemes = ref(false);
const loadingModels = ref(false);
let pollTimer = null;
let pollSeq = 0;

const selectedItem = computed(() => modelList.value.find((m) => m.name === modelName.value) || null);

const statusText = computed(() => {
  if (!modelName.value) return "";
  if (store.parentReady) return "模型就绪，可开始编辑";
  const item = selectedItem.value;
  if (!item) return "读取模型状态…";
  if (item.loadStage === "failed") return "模型加载失败";
  if (item.loadStatus) return "模型就绪，可开始编辑";
  return item.loadMessage || "模型后台加载中…";
});

function persistSelection() {
  selectionStore.setSelection(PAGE_KEY, { sourceMode: "simulation", scheme: scheme.value, model: modelName.value });
}

async function fetchSchemes() {
  loadingSchemes.value = true;
  try {
    const res = await getSchemeList({});
    schemeList.value = Array.isArray(res?.data) ? res.data : [];
  } finally {
    loadingSchemes.value = false;
  }
}

async function fetchModels() {
  if (!scheme.value) {
    modelList.value = [];
    return [];
  }
  loadingModels.value = true;
  try {
    const res = await getModelList({ schemeName: scheme.value });
    modelList.value = Array.isArray(res?.data) ? res.data : [];
    return modelList.value;
  } finally {
    loadingModels.value = false;
  }
}

function stopPolling() {
  pollSeq += 1;
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = null;
}

async function ensureLoadedAndActivate(name) {
  stopPolling();
  const seq = pollSeq;
  const current = modelList.value.find((m) => m.name === name);
  if (current?.loadStatus) {
    await store.setParentModel(name, true);
    return;
  }
  await store.setParentModel(name, false);
  try {
    await loadModel({ name });
  } catch (e) {
    /* loadModel 幂等，报错继续轮询 */
  }
  const poll = async () => {
    if (seq !== pollSeq) return;
    const list = await fetchModels();
    const item = list.find((m) => m.name === name);
    if (item?.loadStatus) {
      store.markParentReady();
      await store.setParentModel(name, true);
      return;
    }
    if (item?.loadStage === "failed") {
      ElMessage.error(`模型加载失败：${item.loadMessage || name}`);
      return;
    }
    pollTimer = setTimeout(poll, 3000);
  };
  pollTimer = setTimeout(poll, 3000);
}

async function handleSchemeChange() {
  modelName.value = "";
  stopPolling();
  await store.setParentModel("", false);
  persistSelection();
  await fetchModels();
}

async function handleModelChange(name) {
  persistSelection();
  if (!name) {
    stopPolling();
    await store.setParentModel("", false);
    return;
  }
  const item = modelList.value.find((m) => m.name === name);
  if (item && !item.cuttable) {
    ElMessage.warning("该模型缺少 output_plans，不能作为线网优化母本");
    modelName.value = "";
    return;
  }
  await ensureLoadedAndActivate(name);
}

/**
 * 决定进入本模块时的母本：默认沿用“当前模型”（运行监测/客流分析正在查看的仿真模型），
 * 从而做到“点击线网优化不改变当前模型状态，直接以当前模型为母本”。
 * 若正在编辑草稿则不打断，沿用会话中已确立的母本。
 */
function pickDesiredSelection() {
  // 已有在编草稿：保持当前母本，避免切换母本清空草稿
  if (store.parentModel && (store.draft.area || store.draft.edits.length)) {
    const own = selectionStore.getSelection(PAGE_KEY);
    return { scheme: own.scheme, model: store.parentModel };
  }
  // 默认采用当前模型（仅仿真模型可作母本）
  const current = selectionStore.getSelection(CURRENT_MODEL_KEY);
  if (current.sourceMode !== "real" && current.scheme && current.model) {
    return { scheme: current.scheme, model: current.model };
  }
  // 兜底：本模块上次的选择
  const own = selectionStore.getSelection(PAGE_KEY);
  return { scheme: own.scheme, model: own.model };
}

onMounted(async () => {
  await fetchSchemes();
  const desired = pickDesiredSelection();
  if (desired.scheme && schemeList.value.includes(desired.scheme)) {
    scheme.value = desired.scheme;
    await fetchModels();
    const item = desired.model ? modelList.value.find((m) => m.name === desired.model) : null;
    if (item && item.cuttable) {
      modelName.value = desired.model;
      await ensureLoadedAndActivate(desired.model);
      return;
    }
    // 当前模型不可切分（缺 output_plans）时，保留其方案，等待用户在下拉里另选可作母本的模型
  }
  if (!scheme.value && schemeList.value.length === 1) {
    scheme.value = schemeList.value[0];
    await fetchModels();
  }
});

onUnmounted(() => stopPolling());
</script>

<style lang="scss" scoped>
.datebase_box {
  position: fixed;
  top: calc(var(--app-header-height, 64px) / 2);
  right: calc(var(--app-edge, 16px) + 64px);
  z-index: calc(var(--z-header, 100) + 10);
  display: flex;
  align-items: center;
  gap: var(--space-xs, 8px);
  max-width: min(62vw, 680px);
  min-width: 0;
  scale: var(--app-panel-scale, 1);
  transform: translateY(-50%);
  transform-origin: right center;

  .handle {
    cursor: default;
    font-size: 0.95rem;
    font-weight: 600;
    color: #374151;
    text-shadow: none;
    white-space: nowrap;
  }

  .bar-status {
    max-width: 180px;
    font-size: 12px;
    font-weight: 600;
    color: var(--app-ink-weak, #6b7789);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;

    &.ok {
      color: #0f9f6e;
    }
  }

  .el-select {
    width: clamp(150px, 14vw, 210px);

    :deep(.el-input__wrapper) {
      padding: 6px 12px;
      background-color: rgba(251, 253, 255, 0.88) !important;
      border-radius: var(--app-card-radius, 8px);
      box-shadow: 0 0 0 1px var(--app-border-strong, #d6e2f0) inset !important;
      transition: background-color 0.2s ease, box-shadow 0.2s ease;

      &:hover {
        background-color: var(--app-card-bg, #fbfdff) !important;
        box-shadow: 0 0 0 1px rgba(11, 145, 183, 0.45) inset !important;
      }

      &.is-focus {
        background-color: var(--app-card-bg, #fbfdff) !important;
        box-shadow: 0 0 0 1.5px var(--app-cyan, #0b91b7) inset, var(--app-focus-ring, 0 0 0 3px rgba(11, 145, 183, 0.12)) !important;
      }

      .el-input__inner {
        color: var(--app-ink, #12304f) !important;
        font-size: 0.94rem !important;
        font-weight: 500;

        &::placeholder {
          color: rgba(18, 48, 79, 0.5);
        }
      }

      .el-select__caret {
        color: var(--app-cyan, #0b91b7) !important;
        font-size: 14px;
      }
    }
  }

  .model-select {
    width: clamp(190px, 18vw, 260px);
  }
}

.model-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;

  .opt-name {
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .opt-tags {
    display: inline-flex;
    gap: 4px;
  }
}

@media (max-width: 1024px) {
  .datebase_box {
    right: calc(var(--app-edge, 16px) + 36px);
    max-width: 52vw;
  }
}

@media (max-width: 960px) {
  .datebase_box {
    top: calc(var(--app-header-height, 64px) + var(--space-lg, 16px));
    right: var(--app-edge, 16px);
    max-width: calc(100vw - (var(--app-edge, 16px) * 2));
    flex-wrap: wrap;
    justify-content: flex-end;
  }
}

@media (max-width: 640px) {
  .datebase_box {
    left: var(--app-edge, 16px);
    transform: none;

    .handle,
    .bar-status {
      width: 100%;
      text-align: right;
    }

    .el-select {
      width: min(100%, 190px);
    }
  }
}
</style>
