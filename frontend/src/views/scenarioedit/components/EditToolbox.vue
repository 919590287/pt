<template>
  <div class="edit-toolbox">
    <!-- 三大类页签 -->
    <div class="tab-row">
      <button v-for="t in TABS" :key="t.key" :class="['tab-btn', tab === t.key && 'active']" @click="switchTab(t.key)">
        {{ t.label }}
      </button>
    </div>

    <!-- ======== 线路 ======== -->
    <div v-if="tab === 'route'" class="tool-pane">
      <div class="tool-grid">
        <button v-for="t in ROUTE_TOOLS" :key="t.key" :class="['tool-btn', activeForm === t.key && 'active']" @click="openForm(t.key)">
          <span :class="['dot', t.tone]"></span>{{ t.label }}
        </button>
      </div>

      <!-- 目标线路选择（除新增外都需要） -->
      <div v-if="activeForm && activeForm !== 'route.add'" class="target-box">
        <div class="target-head">
          <span>目标线路</span>
          <el-button size="small" :type="store.activeTool === 'pick.line' ? 'primary' : 'default'" @click="togglePickLine">
            {{ store.activeTool === "pick.line" ? "点选中…(点地图线路)" : "地图点选" }}
          </el-button>
        </div>
        <el-select v-model="routePick" filterable placeholder="或搜索线路名称" size="small" class="w-full" @change="applyRoutePick">
          <el-option v-for="r in routeOptions" :key="r.key" :label="r.label" :value="r.key" />
        </el-select>
        <div v-if="store.selectedRoute" class="target-info">
          <b>{{ store.selectedRoute.lineName }}</b>
          <span class="sub">方向 {{ store.selectedRoute.routeId }} · {{ store.selectedRoute.facilities.length }}站 · {{ store.selectedRoute.departures.length }}班/日</span>
        </div>
        <div v-else class="target-empty">未选择线路</div>
      </div>

      <!-- 新增线路 -->
      <div v-if="activeForm === 'route.add'" class="form-box">
        <el-input v-model="routeForm.name" placeholder="线路名称（必填），如：金洲环1线" size="small" />
        <div class="row">
          <span class="lbl">双向运行</span>
          <el-switch v-model="routeForm.bidirectional" size="small" />
          <span class="lbl ml">车型</span>
          <el-select v-model="routeForm.vehiclePreset" size="small" class="flex-1">
            <el-option v-for="v in VEHICLE_PRESETS" :key="v.key" :label="v.name" :value="v.key" />
          </el-select>
        </div>
        <SlotsEditor v-model="routeForm.slots" />
        <div class="draw-row">
          <el-button size="small" :type="store.activeTool === 'draw.route' ? 'primary' : 'default'" @click="startDrawRoute">
            {{ store.activeTool === "draw.route" ? `画走向中（${store.toolDraft.anchors.length}锚点，⌫退点）` : routeForm.path ? "重新画走向" : "① 在地图上画走向" }}
          </el-button>
          <el-button v-if="store.activeTool === 'draw.route'" size="small" type="success" :disabled="!store.toolDraft.pathPreview" :loading="store.toolDraft.snapBusy" @click="finishDrawRoute">
            完成走向
          </el-button>
        </div>
        <p v-if="store.toolDraft.snapError" class="err">{{ store.toolDraft.snapError }}</p>
        <template v-if="routeForm.path">
          <div class="ok-tip">走向已确定：{{ routeForm.path.linkIds.length }} 段路，沿线候选站 {{ routeForm.candidates.length }} 个</div>
          <div class="stops-list">
            <div class="stops-head">
              <span>② 勾选停靠站（按沿线顺序）</span>
              <el-button link size="small" @click="startInlineStop('route.add')">+ 在走向上新增站点</el-button>
            </div>
            <el-checkbox-group v-model="routeForm.stops">
              <div v-for="s in routeForm.candidates" :key="s.id" class="stop-item">
                <el-checkbox :value="s.id" size="small">{{ s.name }} <span class="dist">{{ s.distanceM }}m</span></el-checkbox>
              </div>
            </el-checkbox-group>
          </div>
        </template>
        <div class="confirm-row">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="primary" size="small" :disabled="!canConfirmRouteAdd" @click="confirmRouteAdd">✓ 加入修改清单</el-button>
        </div>
      </div>

      <!-- 调整走向 -->
      <div v-else-if="activeForm === 'route.modify.alignment'" class="form-box">
        <p class="hint">重画所选方向的完整走向，沿线站点重新勾选。</p>
        <div class="draw-row">
          <el-button size="small" :disabled="!store.selectedRoute" :type="store.activeTool === 'draw.route' ? 'primary' : 'default'" @click="startDrawRoute">
            {{ store.activeTool === "draw.route" ? `画走向中（${store.toolDraft.anchors.length}锚点）` : alignForm.path ? "重新画走向" : "在地图上画新走向" }}
          </el-button>
          <el-button v-if="store.activeTool === 'draw.route'" size="small" type="success" :disabled="!store.toolDraft.pathPreview" @click="finishDrawAlign">完成走向</el-button>
        </div>
        <template v-if="alignForm.path">
          <div class="ok-tip">新走向 {{ alignForm.path.linkIds.length }} 段路</div>
          <div class="stops-list">
            <div class="stops-head"><span>勾选停靠站</span></div>
            <el-checkbox-group v-model="alignForm.stops">
              <div v-for="s in alignForm.candidates" :key="s.id" class="stop-item">
                <el-checkbox :value="s.id" size="small">{{ s.name }} <span class="dist">{{ s.distanceM }}m</span></el-checkbox>
              </div>
            </el-checkbox-group>
          </div>
        </template>
        <div class="confirm-row">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="primary" size="small" :disabled="!store.selectedRoute || !alignForm.path || alignForm.stops.length < 2" @click="confirmAlignment">✓ 加入修改清单</el-button>
        </div>
      </div>

      <!-- 调整停靠 -->
      <div v-else-if="activeForm === 'route.modify.stops'" class="form-box">
        <p class="hint">勾选=停靠，取消=跳站；沿线80米内未停靠的站也可勾选加停。</p>
        <div v-if="stopsForm.candidates.length" class="stops-list tall">
          <el-checkbox-group v-model="stopsForm.stops">
            <div v-for="s in stopsForm.candidates" :key="s.id" class="stop-item">
              <el-checkbox :value="s.id" size="small">
                {{ s.name }} <span v-if="s.served" class="served">现停</span><span class="dist">{{ s.distanceM }}m</span>
              </el-checkbox>
            </div>
          </el-checkbox-group>
        </div>
        <div v-else class="target-empty">请先选择目标线路</div>
        <div class="confirm-row">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="primary" size="small" :disabled="stopsForm.stops.length < 2" @click="confirmStops">✓ 加入修改清单</el-button>
        </div>
      </div>

      <!-- 发车间隔 / 运营时间 -->
      <div v-else-if="activeForm === 'ops.headway' || activeForm === 'ops.serviceHours'" class="form-box">
        <p class="hint">{{ activeForm === "ops.headway" ? "按时段设置发车间隔，全天班次将重排。" : "调整首末班与服务时段，班次将按间隔重排。" }}</p>
        <SlotsEditor v-model="opsForm.slots" />
        <div class="confirm-row">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="primary" size="small" :disabled="!store.selectedRoute || !opsForm.slots.length" @click="confirmOps(activeForm)">✓ 加入修改清单</el-button>
        </div>
      </div>

      <!-- 更换车型 -->
      <div v-else-if="activeForm === 'ops.vehicleType'" class="form-box">
        <el-select v-model="opsForm.vehiclePreset" size="small" class="w-full">
          <el-option v-for="v in VEHICLE_PRESETS" :key="v.key" :label="v.name" :value="v.key" />
        </el-select>
        <div class="confirm-row">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="primary" size="small" :disabled="!store.selectedRoute" @click="confirmVehicleType">✓ 加入修改清单</el-button>
        </div>
      </div>

      <!-- 删除线路 -->
      <div v-else-if="activeForm === 'route.delete'" class="form-box">
        <el-radio-group v-model="deleteForm.scope" size="small">
          <el-radio value="line">整条线路（含所有方向）</el-radio>
          <el-radio value="route">仅当前方向</el-radio>
        </el-radio-group>
        <div class="confirm-row">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="danger" size="small" :disabled="!store.selectedRoute" @click="confirmRouteDelete">✓ 加入修改清单</el-button>
        </div>
      </div>
    </div>

    <!-- ======== 站点 ======== -->
    <div v-else-if="tab === 'stop'" class="tool-pane">
      <div class="tool-grid">
        <button v-for="t in STOP_TOOLS" :key="t.key" :class="['tool-btn', activeForm === t.key && 'active']" @click="openForm(t.key)">
          <span :class="['dot', t.tone]"></span>{{ t.label }}
        </button>
      </div>

      <!-- 新增站点 -->
      <div v-if="activeForm === 'stop.add'" class="form-box">
        <el-input v-model="stopForm.name" placeholder="站点名称，如：科技园北站" size="small" />
        <p class="hint">在地图上点击站点位置，自动吸附到最近路段。</p>
        <div v-if="store.toolDraft.placedPoint" class="ok-tip">
          已定位：吸附路段 {{ store.toolDraft.placedPoint.linkId }}（偏移 {{ store.toolDraft.placedPoint.distanceM }}m），可重新点击调整
        </div>
        <p v-if="store.toolDraft.snapError" class="err">{{ store.toolDraft.snapError }}</p>
        <div class="confirm-row">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="primary" size="small" :disabled="!store.toolDraft.placedPoint" @click="confirmStopAdd">✓ 加入修改清单</el-button>
        </div>
      </div>

      <!-- 修改站点 -->
      <div v-else-if="activeForm === 'stop.move'" class="form-box">
        <StopTargetBox :store="store" @pick="store.setTool('pick.stop', { keepForm: true })" />
        <template v-if="store.selectedStop">
          <el-input v-model="stopForm.name" :placeholder="`改名（当前：${store.selectedStop.name}）`" size="small" />
          <el-button size="small" :type="store.activeTool === 'place.stop' ? 'primary' : 'default'" @click="store.setTool('place.stop', { keepForm: true })">
            {{ store.toolDraft.placedPoint ? "重新选择新位置" : "在地图上选择新位置（可选）" }}
          </el-button>
          <div v-if="store.toolDraft.placedPoint" class="ok-tip">新位置已定位（吸附 {{ store.toolDraft.placedPoint.linkId }}）</div>
        </template>
        <div class="confirm-row">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="primary" size="small" :disabled="!store.selectedStop || (!stopForm.name && !store.toolDraft.placedPoint)" @click="confirmStopMove">✓ 加入修改清单</el-button>
        </div>
      </div>

      <!-- 删除站点 -->
      <div v-else-if="activeForm === 'stop.delete'" class="form-box">
        <StopTargetBox :store="store" @pick="store.setTool('pick.stop', { keepForm: true })" />
        <div v-if="store.selectedStop" class="affected">
          经过线路 {{ affectedLinesOfStop.length }} 条：{{ affectedLinesOfStop.slice(0, 6).join("、") }}<span v-if="affectedLinesOfStop.length > 6">…</span>
          <br />删除后这些线路改为跳站（走向不变）。
        </div>
        <div class="confirm-row">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="danger" size="small" :disabled="!store.selectedStop" @click="confirmStopDelete">✓ 加入修改清单</el-button>
        </div>
      </div>
    </div>

    <!-- ======== 路网 ======== -->
    <div v-else class="tool-pane">
      <div class="tool-grid">
        <button v-for="t in LINK_TOOLS" :key="t.key" :class="['tool-btn', activeForm === t.key && 'active']" @click="openForm(t.key)">
          <span :class="['dot', t.tone]"></span>{{ t.label }}
        </button>
      </div>

      <!-- 新增路段 -->
      <div v-if="activeForm === 'link.add'" class="form-box">
        <p class="hint">在地图上逐点画线（端点60米内自动吸附路网节点，⌫退点）。</p>
        <div class="row">
          <span class="lbl">双向</span>
          <el-switch v-model="linkForm.bidirectional" size="small" />
          <span class="lbl ml">车道数</span>
          <el-input-number v-model="linkForm.lanes" :min="1" :max="6" size="small" />
        </div>
        <div class="row">
          <span class="lbl">限速</span>
          <el-input-number v-model="linkForm.freespeedKmh" :min="10" :max="120" :step="10" size="small" />
          <span class="lbl">km/h</span>
          <span class="lbl ml">允许公交</span>
          <el-switch v-model="linkForm.allowBus" size="small" />
        </div>
        <div class="ok-tip" v-if="store.toolDraft.anchors.length > 1">已画 {{ store.toolDraft.anchors.length }} 个顶点</div>
        <div class="confirm-row">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="primary" size="small" :disabled="store.toolDraft.anchors.length < 2" @click="confirmLinkAdd">✓ 加入修改清单</el-button>
        </div>
      </div>

      <!-- 修改路段属性 -->
      <div v-else-if="activeForm === 'link.modify'" class="form-box">
        <p class="hint">在地图上点选路段（再次点击取消选择），可连续多选。</p>
        <div class="ok-tip" v-if="store.toolDraft.pickedLinks.length">已选 {{ store.toolDraft.pickedLinks.length }} 条路段</div>
        <div class="row">
          <el-checkbox v-model="linkForm.setSpeed" size="small">限速</el-checkbox>
          <el-input-number v-model="linkForm.freespeedKmh" :disabled="!linkForm.setSpeed" :min="10" :max="120" :step="10" size="small" />
          <span class="lbl">km/h</span>
        </div>
        <div class="row">
          <el-checkbox v-model="linkForm.setLanes" size="small">车道数</el-checkbox>
          <el-input-number v-model="linkForm.lanes" :disabled="!linkForm.setLanes" :min="1" :max="6" size="small" />
        </div>
        <div class="row">
          <span class="lbl">同时应用到反方向</span>
          <el-switch v-model="linkForm.includeReverse" size="small" />
        </div>
        <div class="confirm-row">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="primary" size="small" :disabled="!store.toolDraft.pickedLinks.length || (!linkForm.setSpeed && !linkForm.setLanes)" @click="confirmLinkModify">✓ 加入修改清单</el-button>
        </div>
      </div>

      <!-- 删除路段 -->
      <div v-else-if="activeForm === 'link.delete'" class="form-box">
        <p class="hint">在地图上点选要删除的路段，可连续多选。</p>
        <div class="ok-tip" v-if="store.toolDraft.pickedLinks.length">已选 {{ store.toolDraft.pickedLinks.length }} 条路段</div>
        <div class="row">
          <span class="lbl">同时删除反方向</span>
          <el-switch v-model="linkForm.includeReverse" size="small" />
        </div>
        <div v-if="affectedLinesOfPickedLinks.length" class="affected warn">
          ⚠ 这些路段被 {{ affectedLinesOfPickedLinks.length }} 条线路经过：{{ affectedLinesOfPickedLinks.slice(0, 5).join("、") }}<span v-if="affectedLinesOfPickedLinks.length > 5">…</span>
          <br />生成前需先调整这些线路走向，否则校验将阻断。
        </div>
        <div class="confirm-row">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="danger" size="small" :disabled="!store.toolDraft.pickedLinks.length" @click="confirmLinkDelete">✓ 加入修改清单</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { useScenarioEditStore } from "../store";
import { VEHICLE_PRESETS, presetToVehicleType, slotsFromDepartures, stopsAlongPath } from "../utils";
import SlotsEditor from "./SlotsEditor.vue";

const store = useScenarioEditStore();

const TABS = [
  { key: "route", label: "线路优化" },
  { key: "stop", label: "站点优化" },
  { key: "link", label: "路网调整" },
];

const ROUTE_TOOLS = [
  { key: "route.add", label: "新增线路", tone: "add" },
  { key: "route.modify.alignment", label: "调整走向", tone: "modify" },
  { key: "route.modify.stops", label: "调整停靠", tone: "modify" },
  { key: "ops.headway", label: "发车间隔", tone: "modify" },
  { key: "ops.serviceHours", label: "运营时间", tone: "modify" },
  { key: "ops.vehicleType", label: "更换车型", tone: "modify" },
  { key: "route.delete", label: "删除线路", tone: "delete" },
];
const STOP_TOOLS = [
  { key: "stop.add", label: "新增站点", tone: "add" },
  { key: "stop.move", label: "修改站点", tone: "modify" },
  { key: "stop.delete", label: "删除站点", tone: "delete" },
];
const LINK_TOOLS = [
  { key: "link.add", label: "新增路段", tone: "add" },
  { key: "link.modify", label: "路段属性", tone: "modify" },
  { key: "link.delete", label: "删除路段", tone: "delete" },
];

const tab = ref("route");
const activeForm = ref("");
const routePick = ref("");

// ---------------- 表单状态 ----------------
const routeForm = reactive({
  name: "",
  bidirectional: true,
  vehiclePreset: "std12",
  slots: [{ from: "06:30", to: "22:00", headwayMin: 10 }],
  path: null, // {linkIds, geometry}
  reversePath: null,
  candidates: [],
  stops: [],
  pendingNewStops: [], // [{editId, stopId}]
});
const alignForm = reactive({ path: null, candidates: [], stops: [] });
const stopsForm = reactive({ candidates: [], stops: [] });
const opsForm = reactive({ slots: [], vehiclePreset: "std12" });
const deleteForm = reactive({ scope: "line" });
const stopForm = reactive({ name: "" });
const linkForm = reactive({
  bidirectional: true,
  lanes: 2,
  freespeedKmh: 40,
  allowBus: true,
  setSpeed: true,
  setLanes: false,
  includeReverse: true,
});

const routeOptions = computed(() => {
  const opts = [];
  for (const [key, r] of store.routeIndex.entries()) {
    opts.push({ key, label: `${r.lineName}（${r.routeId}）` });
  }
  return opts;
});

const affectedLinesOfStop = computed(() => {
  const stopId = store.selection.stopId;
  if (!stopId) return [];
  const names = new Set();
  for (const r of store.routeIndex.values()) {
    if (r.facilities.some((f) => f.facilityId === stopId)) names.add(r.lineName);
  }
  return [...names];
});

const affectedLinesOfPickedLinks = computed(() => {
  const picked = new Set(store.toolDraft.pickedLinks.map((l) => l.linkId));
  if (!picked.size) return [];
  const names = new Set();
  for (const r of store.routeIndex.values()) {
    if (r.linkIds?.some((id) => picked.has(id))) names.add(r.lineName);
  }
  return [...names];
});

const canConfirmRouteAdd = computed(() =>
  routeForm.name.trim() && routeForm.path && routeForm.stops.length >= 2 && routeForm.slots.length > 0
);

// ---------------- 通用 ----------------
function switchTab(key) {
  tab.value = key;
  closeForm();
}

function openForm(kind) {
  activeForm.value = kind;
  store.setTool("");
  if (kind === "stop.add") {
    stopForm.name = "";
    store.setTool("place.stop");
  }
  if (kind === "stop.move" || kind === "stop.delete") {
    stopForm.name = "";
    if (!store.selectedStop) store.setTool("pick.stop", { keepForm: true });
  }
  if (kind === "link.add") {
    store.setTool("draw.link");
  }
  if (kind === "link.modify" || kind === "link.delete") {
    store.setTool("pick.link");
  }
  if (kind === "ops.headway" || kind === "ops.serviceHours") {
    opsForm.slots = store.selectedRoute ? slotsFromDepartures(store.selectedRoute.departures) : [{ from: "06:30", to: "22:00", headwayMin: 10 }];
    if (!store.selectedRoute) store.setTool("pick.line", { keepForm: true });
  }
  if (kind === "ops.vehicleType" || kind === "route.delete" || kind === "route.modify.alignment") {
    if (!store.selectedRoute) store.setTool("pick.line", { keepForm: true });
  }
  if (kind === "route.modify.stops") {
    if (!store.selectedRoute) {
      store.setTool("pick.line", { keepForm: true });
    } else {
      rebuildStopsFormCandidates();
    }
  }
  if (kind === "route.add") {
    routeForm.path = null;
    routeForm.reversePath = null;
    routeForm.candidates = [];
    routeForm.stops = [];
    routeForm.pendingNewStops = [];
  }
}

function closeForm() {
  activeForm.value = "";
  store.setTool("");
}

function togglePickLine() {
  if (store.activeTool === "pick.line") {
    store.setTool("");
  } else {
    store.setTool("pick.line", { keepForm: true });
  }
}

function applyRoutePick(key) {
  const [lineId, routeId] = key.split("||");
  store.selectRoute(lineId, routeId);
}

// 选中线路变化时联动表单
watch(() => [store.selection.lineId, store.selection.routeId], () => {
  routePick.value = store.selection.lineId ? `${store.selection.lineId}||${store.selection.routeId}` : "";
  if (activeForm.value === "route.modify.stops" && store.selectedRoute) {
    rebuildStopsFormCandidates();
  }
  if ((activeForm.value === "ops.headway" || activeForm.value === "ops.serviceHours") && store.selectedRoute) {
    opsForm.slots = slotsFromDepartures(store.selectedRoute.departures);
  }
});

// ---------------- 线路工具 ----------------
function startDrawRoute() {
  store.setTool("draw.route", { keepForm: true });
}

async function finishDrawRoute() {
  const preview = store.toolDraft.pathPreview;
  if (!preview) return;
  routeForm.path = { linkIds: preview.linkIds, geometry: preview.geometry };
  routeForm.candidates = stopsAlongPath(preview.geometry, store.stopIndex, 80);
  routeForm.stops = routeForm.candidates.map((c) => c.id);
  // 双向：按锚点倒序再寻径
  if (routeForm.bidirectional) {
    try {
      const { optSnapRoute } = await import("@/api/optimization");
      const res = await optSnapRoute({
        parentModel: store.parentModel,
        draftId: store.draft.draftId || "",
        anchors: [...store.toolDraft.anchors].reverse(),
      });
      routeForm.reversePath = res?.data || null;
    } catch (e) {
      routeForm.reversePath = null;
      ElMessage.warning("反向走向寻径失败，将按单向创建（可稍后为反向单独新增）");
    }
  }
  store.setTool("");
  ElMessage.success("走向已确定，请勾选停靠站");
}

function confirmRouteAdd() {
  const stopsOrdered = routeForm.candidates.filter((c) => routeForm.stops.includes(c.id)).map((c) => c.id);
  const directions = [{ stops: stopsOrdered, linkIds: routeForm.path.linkIds, geometry: routeForm.path.geometry }];
  if (routeForm.bidirectional && routeForm.reversePath) {
    directions.push({ stops: [...stopsOrdered].reverse(), linkIds: routeForm.reversePath.linkIds, geometry: routeForm.reversePath.geometry });
  }
  const deps = routeForm.pendingNewStops.filter((p) => routeForm.stops.includes(p.stopId)).map((p) => p.editId);
  store.addEdit({
    kind: "route.add",
    name: routeForm.name.trim(),
    params: {
      name: routeForm.name.trim(),
      bidirectional: routeForm.bidirectional && Boolean(routeForm.reversePath),
      slots: routeForm.slots,
      vehicleType: presetToVehicleType(routeForm.vehiclePreset),
      opSpeedKmh: 20,
      dwellSec: 30,
    },
    geometry: { directions },
    deps,
  });
  ElMessage.success(`新增线路「${routeForm.name}」已加入清单`);
  closeForm();
}

function finishDrawAlign() {
  const preview = store.toolDraft.pathPreview;
  if (!preview) return;
  alignForm.path = { linkIds: preview.linkIds, geometry: preview.geometry };
  alignForm.candidates = stopsAlongPath(preview.geometry, store.stopIndex, 80);
  const served = new Set((store.selectedRoute?.facilities || []).map((f) => f.facilityId));
  alignForm.stops = alignForm.candidates.filter((c) => served.has(c.id)).map((c) => c.id);
  if (alignForm.stops.length < 2) {
    alignForm.stops = alignForm.candidates.map((c) => c.id);
  }
  store.setTool("");
}

function confirmAlignment() {
  const r = store.selectedRoute;
  const stopsOrdered = alignForm.candidates.filter((c) => alignForm.stops.includes(c.id)).map((c) => c.id);
  store.addEdit({
    kind: "route.modify.alignment",
    name: `${r.lineName}（${r.routeId}）`,
    target: { lineId: r.lineId, routeId: r.routeId },
    params: { opSpeedKmh: 20, dwellSec: 30 },
    geometry: { stops: stopsOrdered, linkIds: alignForm.path.linkIds, directions: [{ stops: stopsOrdered, linkIds: alignForm.path.linkIds, geometry: alignForm.path.geometry }] },
  });
  ElMessage.success("走向调整已加入清单");
  alignForm.path = null;
  closeForm();
}

function rebuildStopsFormCandidates() {
  const r = store.selectedRoute;
  if (!r?.geometry) {
    stopsForm.candidates = [];
    stopsForm.stops = [];
    return;
  }
  const served = new Set(r.facilities.map((f) => f.facilityId));
  const along = stopsAlongPath(r.geometry, store.stopIndex, 80);
  // 确保现有停靠站都在列（几何抽稀导致的漏检兜底）
  const byId = new Map(along.map((c) => [c.id, c]));
  r.facilities.forEach((f, i) => {
    if (!byId.has(f.facilityId)) {
      along.push({ id: f.facilityId, name: f.facilityName || f.facilityId, distanceM: 0, order: -1000 + i });
    }
  });
  along.sort((a, b) => a.order - b.order);
  stopsForm.candidates = along.map((c) => ({ ...c, served: served.has(c.id) }));
  stopsForm.stops = r.facilities.map((f) => f.facilityId);
}

function confirmStops() {
  const r = store.selectedRoute;
  const stopsOrdered = stopsForm.candidates.filter((c) => stopsForm.stops.includes(c.id)).map((c) => c.id);
  store.addEdit({
    kind: "route.modify.stops",
    name: `${r.lineName}（${r.routeId}）`,
    target: { lineId: r.lineId, routeId: r.routeId },
    params: { stops: stopsOrdered },
  });
  ElMessage.success("停靠调整已加入清单");
  closeForm();
}

function confirmOps(kind) {
  const r = store.selectedRoute;
  store.addEdit({
    kind,
    name: `${r.lineName}（${r.routeId}）`,
    target: { lineId: r.lineId, routeId: r.routeId },
    params: { slots: opsForm.slots },
  });
  ElMessage.success("已加入清单");
  closeForm();
}

function confirmVehicleType() {
  const r = store.selectedRoute;
  store.addEdit({
    kind: "ops.vehicleType",
    name: `${r.lineName}（${r.routeId}）`,
    target: { lineId: r.lineId, routeId: r.routeId },
    params: { vehicleType: presetToVehicleType(opsForm.vehiclePreset) },
  });
  ElMessage.success("车型修改已加入清单");
  closeForm();
}

function confirmRouteDelete() {
  const r = store.selectedRoute;
  store.addEdit({
    kind: "route.delete",
    name: r.lineName,
    target: deleteForm.scope === "line" ? { lineId: r.lineId } : { lineId: r.lineId, routeIds: [r.routeId] },
  });
  ElMessage.success("删除线路已加入清单");
  closeForm();
}

// ---------------- 站点工具 ----------------
function confirmStopAdd() {
  const p = store.toolDraft.placedPoint;
  const edit = store.addEdit({
    kind: "stop.add",
    name: stopForm.name.trim() || "新站点",
    params: { name: stopForm.name.trim() || "新站点" },
    geometry: { coord: [p.lng, p.lat], linkId: p.linkId },
  });
  ElMessage.success("新增站点已加入清单");
  // 从"新增线路"表单进来的内联加站：自动挂到线路草稿
  const ctx = store.toolContext;
  if (ctx?.returnTo === "route.add") {
    const stopId = `opt_s_${edit.id}`;
    routeForm.pendingNewStops.push({ editId: edit.id, stopId });
    routeForm.candidates.push({ id: stopId, name: edit.name, distanceM: 0, order: Number.MAX_SAFE_INTEGER });
    routeForm.stops.push(stopId);
    tab.value = "route";
    activeForm.value = "route.add";
    store.setTool("");
    return;
  }
  closeForm();
}

function startInlineStop(returnTo) {
  stopForm.name = "";
  activeForm.value = "stop.add";
  tab.value = "stop";
  store.setTool("place.stop", { returnTo });
  ElMessage.info("在地图走向附近点击新站位置，确认后自动回到线路表单");
}

function confirmStopMove() {
  const s = store.selectedStop;
  const p = store.toolDraft.placedPoint;
  store.addEdit({
    kind: "stop.move",
    name: s.name,
    target: { stopId: s.id },
    params: stopForm.name.trim() ? { name: stopForm.name.trim() } : {},
    geometry: p ? { coord: [p.lng, p.lat], linkId: p.linkId } : { coord: [s.lng, s.lat] },
  });
  ElMessage.success("站点修改已加入清单");
  closeForm();
}

function confirmStopDelete() {
  const s = store.selectedStop;
  store.addEdit({
    kind: "stop.delete",
    name: s.name,
    target: { stopId: s.id },
  });
  ElMessage.success("删除站点已加入清单");
  closeForm();
}

// ---------------- 路网工具 ----------------
function confirmLinkAdd() {
  const anchors = [...store.toolDraft.anchors];
  const nodeSnaps = store.toolDraft.pickedLinks || [];
  const first = nodeSnaps.find((n) => n.index === 0);
  const last = nodeSnaps.find((n) => n.index === anchors.length - 1);
  store.addEdit({
    kind: "link.add",
    name: `新路段（${anchors.length}顶点）`,
    params: {
      bidirectional: linkForm.bidirectional,
      lanes: linkForm.lanes,
      freespeedKmh: linkForm.freespeedKmh,
      capacityPerLane: 1200,
      modes: linkForm.allowBus ? ["car", "bus"] : ["car"],
    },
    geometry: { coords: anchors, fromNodeId: first?.nodeId || null, toNodeId: last?.nodeId || null },
  });
  ElMessage.success("新增路段已加入清单");
  closeForm();
}

function pickedLinkIds() {
  const ids = [];
  for (const l of store.toolDraft.pickedLinks) {
    ids.push(l.linkId);
    if (linkForm.includeReverse && l.reverseLinkId) ids.push(l.reverseLinkId);
  }
  return [...new Set(ids)];
}

function confirmLinkModify() {
  const params = {};
  if (linkForm.setSpeed) params.freespeedKmh = linkForm.freespeedKmh;
  if (linkForm.setLanes) {
    params.lanes = linkForm.lanes;
    params.capacityPerLane = 1200;
  }
  store.addEdit({
    kind: "link.modify",
    name: `路段属性 ×${store.toolDraft.pickedLinks.length}`,
    target: { linkIds: pickedLinkIds() },
    params,
    geometry: { segments: store.toolDraft.pickedLinks.map((l) => l.geometry).filter(Boolean) },
  });
  ElMessage.success("路段属性修改已加入清单");
  closeForm();
}

function confirmLinkDelete() {
  store.addEdit({
    kind: "link.delete",
    name: `删除路段 ×${store.toolDraft.pickedLinks.length}`,
    target: { linkIds: pickedLinkIds() },
    geometry: { segments: store.toolDraft.pickedLinks.map((l) => l.geometry).filter(Boolean) },
  });
  ElMessage.success("删除路段已加入清单");
  closeForm();
}

// 站点目标小组件
const StopTargetBox = defineComponent({
  props: { store: { type: Object, required: true } },
  emits: ["pick"],
  setup(props, { emit }) {
    return () =>
      h("div", { class: "target-box" }, [
        h("div", { class: "target-head" }, [
          h("span", "目标站点"),
          h(
            "button",
            { class: "mini-btn", onClick: () => emit("pick") },
            props.store.activeTool === "pick.stop" ? "点选中…(点地图站点)" : "地图点选"
          ),
        ]),
        props.store.selectedStop
          ? h("div", { class: "target-info" }, [h("b", props.store.selectedStop.name)])
          : h("div", { class: "target-empty" }, "未选择站点（放大地图后点击站点圆点）"),
      ]);
  },
});
</script>

<style lang="scss" scoped>
.edit-toolbox {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.tab-row {
  display: flex;
  gap: 6px;

  .tab-btn {
    flex: 1;
    padding: 7px 0;
    font-size: 13px;
    font-weight: 700;
    border: 1px solid var(--app-border, #dde3ec);
    border-radius: 8px;
    background: transparent;
    cursor: pointer;
    color: var(--app-ink, #223);

    &.active {
      color: #fff;
      background: var(--app-blue, #1569de);
      border-color: var(--app-blue, #1569de);
    }
  }
}

.tool-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;

  .tool-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 5px;
    padding: 7px 2px;
    font-size: 12px;
    border: 1px solid var(--app-border, #e2e8f0);
    border-radius: 8px;
    background: #fff;
    cursor: pointer;

    .dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;

      &.add { background: #16a34a; }
      &.modify { background: #f59e0b; }
      &.delete { background: #dc2626; }
    }

    &.active {
      border-color: var(--app-blue, #1569de);
      background: rgba(21, 105, 222, 0.08);
      font-weight: 700;
    }
  }
}

.form-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
  border: 1px solid var(--app-border, #e2e8f0);
  border-radius: 10px;
  padding: 10px;
  background: rgba(21, 105, 222, 0.03);
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;

  .lbl {
    font-size: 12px;
    color: var(--app-ink-weak, #6b7789);
    white-space: nowrap;
  }

  .ml { margin-left: 8px; }
  .flex-1 { flex: 1; }
}

.hint {
  margin: 0;
  font-size: 12px;
  color: var(--app-ink-weak, #6b7789);
  line-height: 1.5;
}

.err {
  margin: 0;
  font-size: 12px;
  color: #dc2626;
}

.ok-tip {
  font-size: 12px;
  color: #0f9f6e;
  background: rgba(15, 159, 110, 0.08);
  border-radius: 6px;
  padding: 5px 8px;
}

.draw-row {
  display: flex;
  gap: 6px;
}

.stops-list {
  max-height: 180px;
  overflow: auto;
  border: 1px solid var(--app-border, #e2e8f0);
  border-radius: 8px;
  padding: 6px 8px;
  background: #fff;

  &.tall { max-height: 240px; }

  .stops-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 12px;
    font-weight: 700;
    margin-bottom: 4px;
  }

  .stop-item {
    line-height: 1.9;

    .dist {
      margin-left: 6px;
      font-size: 11px;
      color: #94a3b8;
    }

    .served {
      margin-left: 6px;
      font-size: 10px;
      color: #0f9f6e;
      border: 1px solid rgba(15, 159, 110, 0.4);
      border-radius: 4px;
      padding: 0 3px;
    }
  }
}

.confirm-row {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}

:deep(.target-box) {
  border: 1px dashed var(--app-border, #cbd5e1);
  border-radius: 8px;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;

  .target-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 12px;
    font-weight: 700;
  }

  .target-info {
    font-size: 12px;

    .sub {
      margin-left: 6px;
      color: var(--app-ink-weak, #6b7789);
    }
  }

  .target-empty {
    font-size: 12px;
    color: #94a3b8;
  }

  .mini-btn {
    font-size: 12px;
    padding: 3px 8px;
    border: 1px solid var(--app-border, #dde3ec);
    border-radius: 6px;
    background: #fff;
    cursor: pointer;
    color: var(--app-blue, #1569de);
  }
}

.target-box {
  border: 1px dashed var(--app-border, #cbd5e1);
  border-radius: 8px;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;

  .target-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 12px;
    font-weight: 700;
  }

  .target-info {
    font-size: 12px;

    .sub {
      margin-left: 6px;
      color: var(--app-ink-weak, #6b7789);
    }
  }

  .target-empty {
    font-size: 12px;
    color: #94a3b8;
  }
}

.affected {
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-ink-weak, #475569);
  background: rgba(245, 158, 11, 0.08);
  border-radius: 6px;
  padding: 6px 8px;

  &.warn {
    color: #b45309;
  }
}

.w-full { width: 100%; }
</style>
