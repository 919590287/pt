<template>
  <div class="area-panel">
    <div class="mode-row">
      <button :class="['mode-btn', mode === 'draw' && 'active']" @click="mode = 'draw'">手绘多边形</button>
      <button :class="['mode-btn', mode === 'admin' && 'active']" @click="mode = 'admin'">行政区快选</button>
      <button :class="['mode-btn', mode === 'upload' && 'active']" @click="mode = 'upload'">上传文件</button>
    </div>

    <!-- 手绘 -->
    <div v-if="mode === 'draw'" class="pane">
      <p class="hint">
        <template v-if="drawing">已落 {{ store.toolDraft.anchors.length }} 个顶点，⌫ 可退点，至少3点后可完成（ESC 取消）。</template>
        <template v-else-if="hasArea">区域已圈定。重画将替换当前区域。</template>
        <template v-else>点击「开始绘制」后在地图上逐点点击圈出研究区域。</template>
      </p>
      <div class="btn-row">
        <el-button v-if="!drawing" type="primary" size="small" @click="startDraw">开始绘制</el-button>
        <el-button v-else type="success" size="small" :disabled="store.toolDraft.anchors.length < 3" @click="finishDraw">
          完成（{{ store.toolDraft.anchors.length }}点）
        </el-button>
        <el-button v-if="drawing" size="small" @click="cancelDraw">取消</el-button>
        <el-button v-if="hasArea && !drawing" size="small" type="danger" plain @click="clearArea">清除区域</el-button>
      </div>
    </div>

    <!-- 行政区 -->
    <div v-else-if="mode === 'admin'" class="pane">
      <el-select v-model="adminPick" placeholder="选择行政区" size="small" class="w-full" :loading="adminLoading" @change="applyAdmin">
        <el-option v-for="d in adminOptions" :key="d.name" :label="d.name" :value="d.name" />
      </el-select>
      <p class="hint">选择后以该行政区边界作为研究区域。</p>
    </div>

    <!-- 上传 -->
    <div v-else class="pane">
      <div
        class="upload-box"
        :class="{ dragging: draggingFile }"
        @dragover.prevent="draggingFile = true"
        @dragleave="draggingFile = false"
        @drop.prevent="handleDrop"
        @click="fileInput?.click()"
      >
        <input ref="fileInput" type="file" accept=".json,.geojson" class="hidden-input" @change="handleFileSelect" />
        <span v-if="parsing">解析中…</span>
        <span v-else>点击或拖入 GeoJSON（Polygon）文件</span>
      </div>
      <p class="hint">支持 WGS84 经纬度 GeoJSON；SHP 请先转换为 GeoJSON。</p>
    </div>

    <!-- 缓冲距离 -->
    <div class="buffer-row">
      <span class="label">缓冲距离</span>
      <el-slider v-model="bufferM" :min="0" :max="2000" :step="100" size="small" class="buffer-slider" @change="applyBuffer" />
      <span class="value">{{ bufferM }}m</span>
    </div>

    <!-- 区域概览 -->
    <div v-if="hasArea" class="stats-card">
      <div class="stats-title">
        <span>区域概览</span>
        <el-button link size="small" :loading="store.areaStatsLoading" @click="store.refreshAreaStats()">刷新</el-button>
      </div>
      <div class="stats-grid" v-if="store.areaStats">
        <div class="stat"><span class="k">面积</span><span class="v">{{ store.areaStats.areaKm2 }} km²</span></div>
        <div class="stat"><span class="k">触达线路</span><span class="v">{{ store.areaStats.lineTouchCount }} 条</span></div>
        <div class="stat"><span class="k">区域内站点</span><span class="v">{{ store.areaStats.stopCount }} 个</span></div>
        <div class="stat"><span class="k">区域内路段</span><span class="v">{{ store.areaStats.linkCount }} 条</span></div>
      </div>
      <div v-else class="stats-empty">{{ store.areaStatsLoading ? "统计中…" : "母本就绪后自动统计" }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useScenarioEditStore } from "../store";

const store = useScenarioEditStore();
const mode = ref("draw");
const adminPick = ref("");
const adminOptions = ref([]);
const adminLoading = ref(false);
const draggingFile = ref(false);
const parsing = ref(false);
const fileInput = ref(null);
const bufferM = ref(store.draft.area?.bufferM ?? 500);

const hasArea = computed(() => Boolean(store.draft.area?.polygon?.length >= 3));
const drawing = computed(() => store.activeTool === "area.draw");

async function guardEditedArea() {
  if (store.draft.edits.length > 0) {
    await ElMessageBox.confirm("修改区域后将对全部修改项重新校验，是否继续？", "重新圈定研究区域", {
      confirmButtonText: "继续",
      cancelButtonText: "取消",
      type: "warning",
    });
  }
}

async function startDraw() {
  try {
    await guardEditedArea();
  } catch {
    return;
  }
  store.setTool("area.draw");
}

function finishDraw() {
  const pts = [...store.toolDraft.anchors];
  if (pts.length < 3) return;
  store.setTool("");
  store.setArea(pts, "draw", bufferM.value);
  ElMessage.success("研究区域已圈定");
}

function cancelDraw() {
  store.setTool("");
}

async function clearArea() {
  try {
    await guardEditedArea();
  } catch {
    return;
  }
  store.clearAreaOnly();
}

function applyBuffer() {
  if (store.draft.area) {
    store.draft.area.bufferM = bufferM.value;
    store.refreshAreaStats();
  }
}

async function loadAdminOptions() {
  adminLoading.value = true;
  try {
    const module = await import("@/utils/adminDistrictRange.js");
    const listFn = module.listDistrictOptions || module.getDistrictOptions || null;
    if (listFn) {
      const list = await listFn();
      adminOptions.value = (list || []).map((d) => ({ name: d.name || d.label || String(d), ring: d.ring || d.polygon || d.coordinates }));
    }
  } catch (e) {
    adminOptions.value = [];
  } finally {
    adminLoading.value = false;
    if (!adminOptions.value.length) {
      ElMessage.info("行政区边界数据不可用，请使用手绘或上传");
      mode.value = "draw";
    }
  }
}

async function applyAdmin(name) {
  const item = adminOptions.value.find((d) => d.name === name);
  if (!item || !Array.isArray(item.ring) || item.ring.length < 3) {
    ElMessage.warning("该行政区边界数据无效");
    return;
  }
  try {
    await guardEditedArea();
  } catch {
    return;
  }
  store.setArea(item.ring.map((c) => [c[0], c[1]]), "admin", bufferM.value);
}

function handleDrop(e) {
  draggingFile.value = false;
  const file = e.dataTransfer?.files?.[0];
  if (file) parseFile(file);
}

function handleFileSelect(e) {
  const file = e.target.files?.[0];
  if (file) parseFile(file);
  e.target.value = "";
}

async function parseFile(file) {
  const ext = file.name.split(".").pop()?.toLowerCase();
  if (ext !== "json" && ext !== "geojson") {
    ElMessage.error("仅支持 GeoJSON（.json / .geojson）");
    return;
  }
  parsing.value = true;
  try {
    const text = await file.text();
    const json = JSON.parse(text);
    const ring = extractPolygonRing(json);
    if (!ring || ring.length < 3) {
      throw new Error("文件中没有找到 Polygon 几何");
    }
    await guardEditedArea();
    store.setArea(ring.map((c) => [Number(c[0]), Number(c[1])]), "upload", bufferM.value);
    ElMessage.success(`已从 ${file.name} 读取研究区域`);
  } catch (e) {
    if (e !== "cancel") ElMessage.error(e?.message || "文件解析失败");
  } finally {
    parsing.value = false;
  }
}

function extractPolygonRing(json) {
  const geoms = [];
  const collect = (g) => {
    if (!g) return;
    if (g.type === "Polygon") geoms.push(g.coordinates?.[0]);
    if (g.type === "MultiPolygon") geoms.push(g.coordinates?.[0]?.[0]);
  };
  if (json.type === "FeatureCollection") {
    for (const f of json.features || []) collect(f.geometry);
  } else if (json.type === "Feature") {
    collect(json.geometry);
  } else {
    collect(json);
  }
  // 取顶点最多的环
  geoms.sort((a, b) => (b?.length || 0) - (a?.length || 0));
  return geoms[0] || null;
}

if (mode.value === "admin") loadAdminOptions();
watch(mode, (m) => {
  if (m === "admin" && adminOptions.value.length === 0) loadAdminOptions();
  if (m !== "draw" && drawing.value) store.setTool("");
});
</script>

<style lang="scss" scoped>
.area-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.mode-row {
  display: flex;
  gap: 6px;

  .mode-btn {
    flex: 1;
    padding: 6px 4px;
    font-size: 12px;
    border: 1px solid var(--app-border, #dde3ec);
    border-radius: 8px;
    background: transparent;
    color: var(--app-ink, #223);
    cursor: pointer;

    &.active {
      border-color: var(--app-blue, #1569de);
      color: var(--app-blue, #1569de);
      background: rgba(21, 105, 222, 0.08);
      font-weight: 700;
    }
  }
}

.pane .hint {
  margin: 6px 0;
  font-size: 12px;
  color: var(--app-ink-weak, #6b7789);
  line-height: 1.5;
}

.btn-row {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.upload-box {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 68px;
  border: 1.5px dashed var(--app-border, #cbd5e1);
  border-radius: 10px;
  font-size: 12px;
  color: var(--app-ink-weak, #6b7789);
  cursor: pointer;

  &.dragging {
    border-color: var(--app-blue, #1569de);
    background: rgba(21, 105, 222, 0.06);
  }
}

.hidden-input {
  display: none;
}

.buffer-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;

  .label {
    white-space: nowrap;
    color: var(--app-ink-weak, #6b7789);
  }

  .buffer-slider {
    flex: 1;
  }

  .value {
    width: 48px;
    text-align: right;
    font-weight: 600;
  }
}

.stats-card {
  border: 1px solid var(--app-border, #e2e8f0);
  border-radius: 10px;
  padding: 8px 10px;

  .stats-title {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 12px;
    font-weight: 700;
    margin-bottom: 6px;
  }

  .stats-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 6px;

    .stat {
      display: flex;
      justify-content: space-between;
      font-size: 12px;

      .k {
        color: var(--app-ink-weak, #6b7789);
      }

      .v {
        font-weight: 700;
      }
    }
  }

  .stats-empty {
    font-size: 12px;
    color: var(--app-ink-weak, #94a3b8);
  }
}

.w-full {
  width: 100%;
}
</style>
