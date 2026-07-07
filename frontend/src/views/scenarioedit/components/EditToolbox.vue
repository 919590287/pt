<template>
  <div class="edit-toolbox">
    <!-- 双层嵌套导航（与客流分析左侧栏一致，类来自 tokens.css） -->
    <nav class="sidebar-nav netopt-nav" aria-label="线网编辑导航">
      <div v-for="g in GROUPS" :key="g.key" class="menu-group">
        <button
          type="button"
          :class="['nav-item', activeFormGroup === g.key ? 'active' : '']"
          :aria-expanded="expanded[g.key]"
          @click="toggleGroup(g.key)"
        >
          <span class="nav-icon" v-html="g.icon"></span>
          <span class="nav-label">{{ g.label }}</span>
          <span class="chevron-icon" :class="{ expanded: expanded[g.key] }">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="6 9 12 15 18 9"></polyline>
            </svg>
          </span>
        </button>
        <transition name="slide-fade">
          <div v-if="expanded[g.key]" class="sub-nav-list">
            <button
              v-for="t in g.children"
              :key="t.key"
              type="button"
              :class="['sub-nav-item', activeForm === t.key ? 'active' : '']"
              @click="openForm(t.key)"
            >
              <span class="sub-dot"></span>
              <span class="nav-label">{{ t.label }}</span>
            </button>
          </div>
        </transition>
      </div>
    </nav>

    <!-- 当前操作表单（线路/站点走右侧面板 Teleport；路网调整仍内联） -->
    <div v-if="activeForm && !isPanelForm" class="form-area">
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

    <template v-if="teleportTargetReady">
      <!-- 新增/修改线路：Teleport 到右侧面板挂载点，作为右面板的一个视图（确认/取消才切回清单） -->
      <Teleport to="#netopt-route-form-host">
        <div v-if="isRouteForm" class="route-form-panel">
        <div class="rfp-header">
          <span class="rfp-title">{{ isRouteEdit ? `修改线路：${routeEditBaseName}` : "新增线路" }}</span>
          <button v-if="isRouteEdit && store.selectedRoute" class="rfp-del" type="button" @click="deleteSelectedRoute">删除此线路</button>
        </div>
        <el-scrollbar class="rfp-body">
          <!-- 修改线路：尚未选中线路 → 提示先搜索 -->
          <div v-if="isRouteEdit && !store.selectedRoute" class="rfp-empty">
            <div class="rfp-empty-icon">🔍</div>
            <p><b>请用左上角搜索框选中要修改的线路</b></p>
            <p class="sub">选中后，这里会自动载入该线路的名称、发车时段与站序，可直接编辑。</p>
          </div>
          <div v-else class="rfp-body-inner">
            <el-input v-model="routeForm.name" placeholder="线路名称（必填），如：金洲环1线" size="small" />
            <p v-if="autoLineName && pickedStopList.length >= 2" class="auto-name">
              将命名为 <b>{{ autoLineName }}</b>
              <template v-if="routeForm.bidirectional && routeForm.reversePath">；反向 <b>{{ routeForm.name.trim() }}（{{ lastStopName }}-{{ firstStopName }}）</b></template>
            </p>
            <div class="row">
              <span class="lbl">双向运行</span>
              <el-switch v-model="routeForm.bidirectional" size="small" />
              <span class="lbl ml">车型</span>
              <el-select v-model="routeForm.vehiclePreset" size="small" class="flex-1">
                <el-option v-for="v in VEHICLE_PRESETS" :key="v.key" :label="v.name" :value="v.key" />
              </el-select>
            </div>
            <SlotsEditor v-model="routeForm.slots" />

            <!-- 站点/走向选择：点站点=停靠站，点路网=途经点（沿路网走），按顺序建线 -->
            <div class="pick-block">
              <div class="pick-head">
                <span>途经站点（{{ pickedStopList.length }}<span v-if="roadPointCount"> · {{ roadPointCount }}路径点</span>）</span>
                <el-button v-if="!pickingStops" size="small" type="primary" @click="startStopPick">
                  {{ store.lineBuilder.anchors.length ? "继续点选" : "在地图上点选" }}
                </el-button>
                <el-button v-else size="small" type="success" @click="finishStopPick">完成点选</el-button>
              </div>
              <p class="hint">
                <template v-if="sessionLabel">{{ sessionLabel }}</template>
                <template v-else><b>点站点</b>=设为停靠站，<b>点路网空白处</b>=加途经点约束走向（沿路网走）。</template>
              </p>
              <div v-if="pickingStops" class="pick-ops">
                <el-button size="small" :disabled="!canPopAnchor" @click="store.popLineAnchor()">撤销上一步</el-button>
                <el-button v-if="gapOpen" size="small" @click="store.sessionAutoConnect()">直接最短路连接</el-button>
              </div>
              <div v-if="pickedStopList.length" class="seq-list">
                <div v-for="(s, i) in pickedStopList" :key="s.anchorIdx" class="seq-row">
                  <span class="seq-no">{{ s.seq }}</span>
                  <span class="seq-name">{{ s.name }}<span v-if="i === 0" class="tag">首发站</span></span>
                  <button class="seq-del" type="button" title="移除" @click="store.removeLineAnchorAt(s.anchorIdx)">✕</button>
                </div>
              </div>
              <div v-else class="seq-empty">尚未选择站点</div>
              <p v-if="lineSnapBusy" class="hint">正在沿路网连接…</p>
              <p v-else-if="lineSnapError" class="err">{{ lineSnapError }}</p>
              <div v-else-if="routeForm.path" class="ok-tip">走向已连通：{{ routeForm.path.linkIds.length }} 段路</div>
            </div>
          </div>
        </el-scrollbar>
        <div class="rfp-footer">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="primary" size="small" :disabled="!canConfirmRoute" @click="confirmRouteForm">✓ 加入修改清单</el-button>
        </div>
        </div>
      </Teleport>

      <!-- 新增站点：右侧面板视图（地图点选位置） -->
      <Teleport to="#netopt-route-form-host">
        <div v-if="activeForm === 'stop.add'" class="route-form-panel">
        <div class="rfp-header"><span class="rfp-title">新增站点</span></div>
        <el-scrollbar class="rfp-body">
          <div class="rfp-body-inner">
            <el-input v-model="stopForm.name" placeholder="站点名称，如：科技园北站" size="small" />
            <p class="hint">在地图上点击站点位置，自动吸附到最近路段（可重复点击调整）。</p>
            <div v-if="store.toolDraft.placedPoint" class="ok-tip">已定位：吸附路段 {{ store.toolDraft.placedPoint.linkId }}（偏移 {{ store.toolDraft.placedPoint.distanceM }}m）</div>
            <p v-if="store.toolDraft.snapError" class="err">{{ store.toolDraft.snapError }}</p>
          </div>
        </el-scrollbar>
        <div class="rfp-footer">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="primary" size="small" :disabled="!store.toolDraft.placedPoint" @click="confirmStopAdd">✓ 加入修改清单</el-button>
        </div>
        </div>
      </Teleport>

      <!-- 修改站点：右侧面板视图（先搜索/点选站点，再改名/移位） -->
      <Teleport to="#netopt-route-form-host">
        <div v-if="activeForm === 'stop.move'" class="route-form-panel">
        <div class="rfp-header"><span class="rfp-title">修改站点{{ store.selectedStop ? `：${store.selectedStop.name}` : "" }}</span></div>
        <el-scrollbar class="rfp-body">
          <div v-if="store.selectedStop" class="rfp-body-inner">
            <el-input v-model="stopForm.name" :placeholder="`改名（当前：${store.selectedStop.name}）`" size="small" />
            <el-button size="small" :type="store.activeTool === 'place.stop' ? 'primary' : 'default'" @click="store.setTool('place.stop', { keepForm: true })">
              {{ store.toolDraft.placedPoint ? "重新选择新位置" : "在地图上选择新位置（可选）" }}
            </el-button>
            <div v-if="store.toolDraft.placedPoint" class="ok-tip">新位置已定位（吸附 {{ store.toolDraft.placedPoint.linkId }}）</div>
            <el-button link size="small" @click="reselectStop">换个站点</el-button>
          </div>
        </el-scrollbar>
        <div class="rfp-footer">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="primary" size="small" :disabled="!store.selectedStop || (!stopForm.name && !store.toolDraft.placedPoint)" @click="confirmStopMove">✓ 加入修改清单</el-button>
        </div>
        </div>
      </Teleport>

      <!-- 删除站点：右侧面板视图（先搜索/点选站点） -->
      <Teleport to="#netopt-route-form-host">
        <div v-if="activeForm === 'stop.delete'" class="route-form-panel">
        <div class="rfp-header"><span class="rfp-title">删除站点{{ store.selectedStop ? `：${store.selectedStop.name}` : "" }}</span></div>
        <el-scrollbar class="rfp-body">
          <div v-if="store.selectedStop" class="rfp-body-inner">
            <div class="affected">
              经过线路 {{ affectedLinesOfStop.length }} 条：{{ affectedLinesOfStop.slice(0, 6).join("、") }}<span v-if="affectedLinesOfStop.length > 6">…</span>
              <br />删除后这些线路改为跳站（走向不变）。
            </div>
            <el-button link size="small" @click="reselectStop">换个站点</el-button>
          </div>
        </el-scrollbar>
        <div class="rfp-footer">
          <el-button size="small" @click="closeForm">取消</el-button>
          <el-button type="danger" size="small" :disabled="!store.selectedStop" @click="confirmStopDelete">✓ 加入删除清单</el-button>
        </div>
        </div>
      </Teleport>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useScenarioEditStore } from "../store";
import { optSnapRoute } from "@/api/optimization";
import { VEHICLE_PRESETS, presetToVehicleType, slotsFromDepartures } from "../utils";
import SlotsEditor from "./SlotsEditor.vue";

const store = useScenarioEditStore();

// 双层嵌套导航（与客流分析一致）。删除线路不是独立工具：搜索选中后按 Delete 键。
const GROUPS = [
  {
    key: "route",
    label: "线路优化",
    icon: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="12" rx="2"></rect><circle cx="7" cy="10" r="1"></circle><circle cx="17" cy="10" r="1"></circle><path d="M6 16v2"></path><path d="M18 16v2"></path></svg>',
    children: [
      { key: "route.add", label: "新增线路" },
      { key: "route.edit", label: "修改线路" },
    ],
  },
  {
    key: "stop",
    label: "站点优化",
    icon: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path><circle cx="12" cy="10" r="3"></circle></svg>',
    children: [
      { key: "stop.add", label: "新增站点" },
      { key: "stop.move", label: "修改站点" },
      { key: "stop.delete", label: "删除站点" },
    ],
  },
  {
    key: "link",
    label: "路网调整",
    icon: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="6" r="2.5"></circle><circle cx="18" cy="18" r="2.5"></circle><path d="M8 7.5c3 2 5 5 8 8.5"></path><path d="M6 8.5V15a3 3 0 0 0 3 3h5.5"></path></svg>',
    children: [
      { key: "link.add", label: "新增路段" },
      { key: "link.modify", label: "路段属性" },
      { key: "link.delete", label: "删除路段" },
    ],
  },
];

const FORM_GROUP = {};
for (const g of GROUPS) for (const t of g.children) FORM_GROUP[t.key] = g.key;

const activeForm = ref("");
const expanded = reactive({ route: true, stop: false, link: false });
const teleportTargetReady = ref(false);

const activeFormGroup = computed(() => FORM_GROUP[activeForm.value] || "");
// 新增/修改线路共用同一套中央弹窗表单
const isRouteForm = computed(() => activeForm.value === "route.add" || activeForm.value === "route.edit");
const isRouteEdit = computed(() => activeForm.value === "route.edit");
// 走右侧面板视图的表单（线路 + 站点）；路网调整仍内联在左侧
const PANEL_FORMS = ["route.add", "route.edit", "stop.add", "stop.move", "stop.delete"];
const isPanelForm = computed(() => PANEL_FORMS.includes(activeForm.value));

/** 修改/删除站点：换个站点（清选中并回到地图点选态，也可继续用搜索框） */
function reselectStop() {
  store.clearSelection();
  store.setTool("pick.stop", { keepForm: true });
}
const routeEditBaseName = computed(() => store.selectedRoute?.lineName || "");

function toggleGroup(key) {
  expanded[key] = !expanded[key];
}

// ---------------- 表单状态 ----------------
const routeForm = reactive({
  name: "",
  bidirectional: true,
  vehiclePreset: "std12",
  slots: [{ from: "06:30", to: "22:00", headwayMin: 10 }],
  path: null, // {linkIds, geometry}
  reversePath: null,
});
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

const canConfirmRoute = computed(() =>
  routeForm.name.trim() && pickedStopList.value.length >= 2 && routeForm.path && routeForm.slots.length > 0
);

// ---------------- 通用 ----------------
/** 带冲突检测的加入清单：冲突时用通俗语言弹窗说明，不加入 */
function guardAdd(payload) {
  const res = store.addEditChecked(payload);
  if (!res.ok) {
    ElMessageBox.alert(res.reason, "无法加入修改清单", { type: "warning", confirmButtonText: "知道了" });
    return null;
  }
  return res.edit;
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
    if (!store.selectedStop) {
      store.setTool("pick.stop", { keepForm: true }); // 也可地图点选
      ElMessage.info(`请用左上角搜索框选中要${kind === "stop.delete" ? "删除" : "修改"}的站点`);
    }
  }
  if (kind === "link.add") {
    store.setTool("draw.link");
  }
  if (kind === "link.modify" || kind === "link.delete") {
    store.setTool("pick.link");
  }
  if (kind === "route.add") {
    routeForm.name = "";
    routeForm.bidirectional = true;
    routeForm.vehiclePreset = "std12";
    routeForm.slots = [{ from: "06:30", to: "22:00", headwayMin: 10 }];
    routeForm.path = null;
    routeForm.reversePath = null;
    lineSnapError.value = "";
    store.clearLineBuilder();
  }
  if (kind === "route.edit") {
    if (store.selectedRoute) {
      prefillRouteEdit(store.selectedRoute); // 已选线路：直接预填
    } else {
      // 先打开面板，提示用左上角搜索框选中线路，选中后自动填入
      store.clearLineBuilder();
      routeForm.path = null;
      routeForm.reversePath = null;
      lineSnapError.value = "";
      ElMessage.info("请用左上角搜索框选中要修改的线路");
    }
  }
}

function closeForm() {
  activeForm.value = "";
  store.setTool("");
  store.clearLineBuilder();
}

// 把当前表单类型同步到 store，供 index.vue 判断（搜索仅定位 / 屏蔽删除键）
watch(activeForm, (v) => { store.activeFormKind = v || ""; }, { immediate: true });

onMounted(async () => {
  await nextTick();
  teleportTargetReady.value = typeof document !== "undefined" && Boolean(document.getElementById("netopt-route-form-host"));
});

onUnmounted(() => {
  teleportTargetReady.value = false;
  store.activeFormKind = "";
});

// 修改线路面板已打开时，搜索选中/换线即填入（不再"搜索自动弹出"，需先点开修改线路）
watch(
  () => (store.selection.type === "route" && store.selection.lineId ? `${store.selection.lineId}||${store.selection.routeId}` : ""),
  (key) => {
    if (key && activeForm.value === "route.edit") prefillRouteEdit(store.selectedRoute);
  }
);

// 修改线路弹窗内：删除该线路
async function deleteSelectedRoute() {
  const r = store.selectedRoute;
  if (!r) return;
  let scope = null;
  try {
    await ElMessageBox.confirm(
      `将把「${r.lineName}」加入删除清单：生成方案时移除，加入后可随时在右侧撤销。也可只删当前方向（${r.routeId}）。`,
      "删除线路",
      { confirmButtonText: "删除整条线路", cancelButtonText: "仅删当前方向", distinguishCancelAndClose: true, type: "warning" }
    );
    scope = "line";
  } catch (action) {
    if (action === "cancel") scope = "route";
    else return;
  }
  const payload = scope === "line"
    ? { kind: "route.delete", name: r.lineName, target: { lineId: r.lineId } }
    : { kind: "route.delete", name: `${r.lineName}（${r.routeId}）`, target: { lineId: r.lineId, routeIds: [r.routeId] } };
  const edit = guardAdd(payload);
  if (!edit) return;
  ElMessage.success(`已加入删除：${payload.name}（可在右侧撤销）`);
  store.clearSelection();
  closeForm();
}

// —— 新增线路：从首发站起点选站点，按站序沿路网自动连成走向 ——
const lineSnapBusy = ref(false);
const lineSnapError = ref("");
let lineSnapSeq = 0;
let lineSnapTimer = null;

function stopName(id) {
  return store.stopIndex.get(id)?.name || id;
}

// 正在点选站点（此时弹窗隐藏、仅留底部操作条）
const pickingStops = computed(
  () => store.activeTool === "pick.stop" && store.toolContext?.purpose === "buildLine"
);

// 地图右键触发的区段编辑会话：操作条提示 + 撤销范围
const SESSION_LABELS = {
  replace: "修改站点：先点选替换的新站点，再沿路网点选途经点连接断开处",
  delete: "删除站点·重连线路：沿路网点选途经点/站点，把断开处连起来",
  insertBefore: "新增上一站：点选新站点，再沿路网点选途经点连接",
  insertAfter: "新增下一站：点选新站点，再沿路网点选途经点连接",
  segment: "修改断面路径：沿路网点选途经点，点站点可加停靠",
};
const sessionLabel = computed(() => SESSION_LABELS[store.lineBuilder.session?.kind] || "");
const canPopAnchor = computed(() =>
  store.lineBuilder.session ? store.lineBuilder.session.added > 0 : store.lineBuilder.anchors.length > 0
);

function startStopPick() {
  store.endLineSession(); // 弹窗内按钮=追加模式
  store.setTool("pick.stop", { purpose: "buildLine", keepForm: true });
}

function finishStopPick() {
  // 断开处还没连接：不允许直接完成（否则结束会话会走整线最短路=自动），提示先连接
  if (gapOpen.value) {
    ElMessage.warning("断开处还没连接：请沿路网点选途经点/站点，或点“直接最短路连接”");
    return;
  }
  store.endLineSession();
  store.setTool(""); // 退出点选 → 弹窗重新出现
}

// ESC 等途径退出点选时，同步结束区段会话
watch(pickingStops, (v) => {
  if (!v) store.endLineSession();
});

// 停靠站列表（锚点中 type==='stop' 的，带原锚点下标与站序号，供列表展示/删除）
const pickedStopList = computed(() => {
  const out = [];
  let seq = 0;
  store.lineBuilder.anchors.forEach((a, idx) => {
    if (a.type === "stop") {
      seq += 1;
      out.push({ anchorIdx: idx, seq, stopId: a.stopId, name: stopName(a.stopId) });
    }
  });
  return out;
});
const roadPointCount = computed(() => store.lineBuilder.anchors.filter((a) => a.type === "road").length);

// 右键站点编辑会话刚开始、断开处尚未点选连接（且未选“直接最短路连接”）→ 保持断开、不自动连
const gapOpen = computed(() => {
  const s = store.lineBuilder.session;
  if (!s || s.added > 0 || s.autoConnect) return false;
  // 删除首/末站：一侧没有相邻站，无需重连，直接按剩余站序整线连
  if (s.kind === "delete" && (s.leftIdx < 0 || s.insertPos >= store.lineBuilder.anchors.length)) return false;
  return true;
});

function anchorCoords(from, to) {
  return store.lineBuilder.anchors
    .slice(from, to)
    .filter((a) => Number.isFinite(a.lng) && Number.isFinite(a.lat))
    .map((a) => [a.lng, a.lat]);
}

async function routeThroughPicked() {
  routeForm.path = null;
  routeForm.reversePath = null;
  store.lineBuilderPath = null;
  lineSnapError.value = "";

  // 断开处未连接：两侧分别沿路网连、中间留断口（不自动补），等用户点选途经点/站点
  if (gapOpen.value) {
    const s = store.lineBuilder.session;
    const left = anchorCoords(0, s.leftIdx + 1);
    const right = anchorCoords(s.insertPos, store.lineBuilder.anchors.length);
    const seq = ++lineSnapSeq;
    lineSnapBusy.value = true;
    try {
      const req = (anchors) => (anchors.length >= 2
        ? optSnapRoute({ parentModel: store.parentModel, draftId: store.draft.draftId || "", anchors })
        : Promise.resolve(null));
      const [ra, rb] = await Promise.all([req(left), req(right)]);
      if (seq !== lineSnapSeq) return;
      const segments = [ra?.data?.geometry, rb?.data?.geometry].filter((g) => Array.isArray(g) && g.length > 1);
      store.lineBuilderPath = { segments }; // 断开预览：两段绿线，中间可见断口
      routeForm.path = null; // 断开状态不可加入清单
      lineSnapError.value = "断开处未连接：沿路网点选途经点或站点将其连起来，或点“直接最短路连接”。";
    } catch (e) {
      if (seq === lineSnapSeq) lineSnapError.value = e?.message || "两侧路径寻径失败";
    } finally {
      if (seq === lineSnapSeq) lineSnapBusy.value = false;
    }
    return;
  }

  // 整线沿路网连（新增线路 / 修改线路 / 会话已点选或选了直接连接）
  const anchors = anchorCoords(0, store.lineBuilder.anchors.length);
  if (anchors.length < 2) return;
  const seq = ++lineSnapSeq;
  lineSnapBusy.value = true;
  try {
    const res = await optSnapRoute({ parentModel: store.parentModel, draftId: store.draft.draftId || "", anchors });
    if (seq !== lineSnapSeq) return;
    routeForm.path = res?.data || null;
    store.lineBuilderPath = res?.data || null;
    if (routeForm.bidirectional) {
      try {
        const rev = await optSnapRoute({ parentModel: store.parentModel, draftId: store.draft.draftId || "", anchors: [...anchors].reverse() });
        if (seq === lineSnapSeq) routeForm.reversePath = rev?.data || null;
      } catch {
        routeForm.reversePath = null;
      }
    }
  } catch (e) {
    if (seq !== lineSnapSeq) return;
    routeForm.path = null;
    store.lineBuilderPath = null;
    lineSnapError.value = e?.message || "相邻锚点之间找不到连续路网，请调整点选顺序或改选位置";
  } finally {
    if (seq === lineSnapSeq) lineSnapBusy.value = false;
  }
}

// 锚点变化（点选站点/路网、撤销）或会话状态变化（开始/直接连接/结束）→ 防抖后沿路网重连
watch(
  () => [store.lineBuilder.anchors.slice(), store.lineBuilder.session],
  () => {
    if (!isRouteForm.value) return;
    if (lineSnapTimer) clearTimeout(lineSnapTimer);
    lineSnapTimer = setTimeout(routeThroughPicked, 300);
  },
  { deep: true }
);

// 双向开关切换 → 重算（补/去反向走向）
watch(() => routeForm.bidirectional, () => {
  if (isRouteForm.value && store.lineBuilder.anchors.length >= 2) routeThroughPicked();
});

// 首发站 / 终点站名称（用于线路名自动后缀）
const firstStopName = computed(() => pickedStopList.value[0]?.name || "");
const lastStopName = computed(() => pickedStopList.value[pickedStopList.value.length - 1]?.name || "");
// 线路名自动加上"（首发站-终点站）"后缀
const autoLineName = computed(() => {
  const base = routeForm.name.trim();
  if (!base || pickedStopList.value.length < 2) return base;
  return `${base}（${firstStopName.value}-${lastStopName.value}）`;
});

function buildDirections() {
  const stopsOrdered = pickedStopList.value.map((s) => s.stopId);
  const directions = [{ stops: stopsOrdered, linkIds: routeForm.path.linkIds, geometry: routeForm.path.geometry }];
  if (routeForm.bidirectional && routeForm.reversePath) {
    directions.push({ stops: [...stopsOrdered].reverse(), linkIds: routeForm.reversePath.linkIds, geometry: routeForm.reversePath.geometry });
  }
  return directions;
}

function routeParams(finalName) {
  return {
    name: finalName,
    bidirectional: routeForm.bidirectional && Boolean(routeForm.reversePath),
    slots: routeForm.slots,
    vehicleType: presetToVehicleType(routeForm.vehiclePreset),
    opSpeedKmh: 20,
    dwellSec: 30,
  };
}

function confirmRouteForm() {
  if (isRouteEdit.value) confirmRouteEdit();
  else confirmRouteAdd();
}

function confirmRouteAdd() {
  if (!routeForm.path) return;
  const finalName = autoLineName.value; // 名称自动带首发站-终点站后缀
  const edit = guardAdd({
    kind: "route.add",
    name: finalName,
    params: routeParams(finalName),
    geometry: { directions: buildDirections() },
    deps: [],
  });
  if (!edit) return;
  ElMessage.success(`新增线路「${finalName}」已加入清单`);
  closeForm();
}

// 修改线路：删旧线 + 按新定义重建（route.replace）
function confirmRouteEdit() {
  if (!routeForm.path) return;
  const r = store.selectedRoute;
  if (!r) return;
  const finalName = autoLineName.value;
  const edit = guardAdd({
    kind: "route.replace",
    name: finalName,
    target: { lineId: r.lineId },
    params: routeParams(finalName),
    geometry: { directions: buildDirections() },
    deps: [],
  });
  if (!edit) return;
  ElMessage.success(`修改线路「${finalName}」已加入清单`);
  store.clearSelection();
  closeForm();
}

/** 判断线路是否双向（含 2 个及以上方向） */
function lineIsBidirectional(lineId) {
  const line = (store.lines || []).find((l) => l.lineId === lineId);
  return (line?.routes?.length || 1) >= 2;
}

/** 用所选线路现状预填修改线路表单（名称/双向/发车时段/站序），并沿路网重连走向 */
function prefillRouteEdit(r) {
  if (!r) return;
  routeForm.name = (r.lineName || "").replace(/（[^）]*）\s*$/, "").trim(); // 去掉已有(首发-终点)后缀避免叠加
  routeForm.bidirectional = lineIsBidirectional(r.lineId);
  routeForm.vehiclePreset = "std12";
  routeForm.slots = slotsFromDepartures(r.departures);
  routeForm.path = null;
  routeForm.reversePath = null;
  lineSnapError.value = "";
  // 现有停靠站作为初始锚点（anchors 变化会触发沿路网重连）
  store.clearLineBuilder();
  for (const f of r.facilities || []) {
    if (f?.facilityId) store.appendLineStop(f.facilityId);
  }
}

// ---------------- 站点工具 ----------------
function confirmStopAdd() {
  const p = store.toolDraft.placedPoint;
  const edit = guardAdd({
    kind: "stop.add",
    name: stopForm.name.trim() || "新站点",
    params: { name: stopForm.name.trim() || "新站点" },
    geometry: { coord: [p.lng, p.lat], linkId: p.linkId },
  });
  if (!edit) return;
  ElMessage.success("新增站点已加入清单");
  closeForm();
}

function confirmStopMove() {
  const s = store.selectedStop;
  const p = store.toolDraft.placedPoint;
  const edit = guardAdd({
    kind: "stop.move",
    name: s.name,
    target: { stopId: s.id },
    params: stopForm.name.trim() ? { name: stopForm.name.trim() } : {},
    geometry: p ? { coord: [p.lng, p.lat], linkId: p.linkId } : { coord: [s.lng, s.lat] },
  });
  if (!edit) return;
  ElMessage.success("站点修改已加入清单");
  closeForm();
}

function confirmStopDelete() {
  const s = store.selectedStop;
  const edit = guardAdd({
    kind: "stop.delete",
    name: s.name,
    target: { stopId: s.id },
  });
  if (!edit) return;
  ElMessage.success("删除站点已加入清单");
  closeForm();
}

// ---------------- 路网工具 ----------------
function confirmLinkAdd() {
  const anchors = [...store.toolDraft.anchors];
  const nodeSnaps = store.toolDraft.pickedLinks || [];
  const first = nodeSnaps.find((n) => n.index === 0);
  const last = nodeSnaps.find((n) => n.index === anchors.length - 1);
  const edit = guardAdd({
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
  if (!edit) return;
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
  const edit = guardAdd({
    kind: "link.modify",
    name: `路段属性 ×${store.toolDraft.pickedLinks.length}`,
    target: { linkIds: pickedLinkIds() },
    params,
    geometry: { segments: store.toolDraft.pickedLinks.map((l) => l.geometry).filter(Boolean) },
  });
  if (!edit) return;
  ElMessage.success("路段属性修改已加入清单");
  closeForm();
}

function confirmLinkDelete() {
  const edit = guardAdd({
    kind: "link.delete",
    name: `删除路段 ×${store.toolDraft.pickedLinks.length}`,
    target: { linkIds: pickedLinkIds() },
    geometry: { segments: store.toolDraft.pickedLinks.map((l) => l.geometry).filter(Boolean) },
  });
  if (!edit) return;
  ElMessage.success("删除路段已加入清单");
  closeForm();
}

</script>

<style lang="scss" scoped>
.edit-toolbox {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* 双层嵌套导航：骨架类（nav-item/sub-nav-list/sub-nav-item）来自 tokens.css 全局 */
.netopt-nav {
  padding: 0;
}

.slide-fade-enter-active,
.slide-fade-leave-active {
  transition: opacity 160ms ease, transform 160ms ease;
}

.slide-fade-enter-from,
.slide-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.form-area {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* 新增/修改线路：作为右侧面板的一个视图（Teleport 到 #netopt-route-form-host，充满面板） */
.route-form-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;

  .rfp-header {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--dm2-space-2);
    padding: 0 0 var(--dm2-space-3);
    border-bottom: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));

    .rfp-title {
      font-size: var(--dm2-text-xl);
      font-weight: var(--dm2-fw-bold);
      line-height: 1.25;
      color: var(--dm2-ink, #1c2024);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .rfp-del {
      flex-shrink: 0;
      padding: 3px 9px;
      border: 1px solid var(--dm2-delete-line, rgba(255, 59, 48, 0.22));
      border-radius: 6px;
      background: transparent;
      color: var(--dm2-delete, #c4291c);
      font-size: var(--dm2-text-sm);
      cursor: pointer;

      &:hover { background: var(--dm2-delete-weak, rgba(255, 59, 48, 0.06)); }
    }
  }

  .rfp-body {
    flex: 1 1 auto;
    min-height: 0;

    .rfp-body-inner {
      display: flex;
      flex-direction: column;
      gap: var(--dm2-space-2);
      padding: var(--dm2-space-3) 0 var(--dm2-space-1);
    }

    .rfp-empty {
      text-align: center;
      padding: 40px 18px;
      color: var(--dm2-muted, #667085);

      .rfp-empty-icon { font-size: 30px; margin-bottom: var(--dm2-space-2); }
      p { margin: var(--dm2-space-1) 0; font-size: var(--dm2-text-base); }
      .sub { font-size: var(--dm2-text-sm); line-height: 1.7; color: var(--dm2-muted-soft, #98a2b3); }
    }
  }

  .rfp-footer {
    flex-shrink: 0;
    display: flex;
    justify-content: flex-end;
    gap: var(--dm2-space-2);
    padding-top: var(--dm2-space-3);
    margin-top: var(--dm2-space-1);
    border-top: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
  }
}

.pick-ops {
  display: flex;
  gap: 6px;
}

.auto-name {
  margin: -2px 0 0;
  font-size: var(--dm2-text-sm);
  line-height: 1.5;
  color: var(--dm2-muted, #667085);

  b { color: var(--dm2-accent, #0071e3); font-weight: var(--dm2-fw-semibold); }
}

/* 新增线路：站点选择 / 站序 */
.pick-block {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-2);
  border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
  border-radius: var(--dm2-radius-sm, 10px);
  padding: var(--dm2-space-2) var(--dm2-space-3);
  background: var(--dm2-surface-sunken, #f4f7fb);

  .pick-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: var(--dm2-text-base);
    font-weight: var(--dm2-fw-bold);
    color: var(--dm2-ink, #1c2024);
  }

  .hint {
    margin: 0;
    font-size: var(--dm2-text-sm);
    line-height: 1.5;
    color: var(--dm2-muted, #667085);
  }

  .seq-list {
    max-height: 200px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .seq-row {
    display: flex;
    align-items: center;
    gap: var(--dm2-space-2);
    padding: 3px 4px;
    border-radius: 6px;

    &:hover { background: rgba(0, 113, 227, 0.06); }

    .seq-no {
      width: 20px;
      height: 20px;
      flex-shrink: 0;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-size: var(--dm2-text-xs);
      font-weight: var(--dm2-fw-bold);
      color: #fff;
      background: var(--dm2-accent, #0071e3);
      border-radius: var(--dm2-radius-pill);
    }

    .seq-name {
      flex: 1;
      font-size: var(--dm2-text-sm);

      .tag {
        margin-left: 6px;
        font-size: var(--dm2-text-xs);
        color: var(--dm2-add, #1a8a3f);
        border: 1px solid var(--dm2-add-line, rgba(52, 199, 89, 0.22));
        border-radius: 4px;
        padding: 0 3px;
      }
    }

    .seq-del {
      border: 0;
      background: transparent;
      color: var(--dm2-muted-soft, #98a2b3);
      cursor: pointer;
      font-size: var(--dm2-text-sm);
      padding: 2px 5px;
      border-radius: 4px;

      &:hover { color: var(--dm2-delete, #c4291c); background: var(--dm2-delete-weak, rgba(255, 59, 48, 0.08)); }
    }
  }

  .seq-empty {
    font-size: var(--dm2-text-sm);
    color: var(--dm2-muted-soft, #98a2b3);
    padding: 2px;
  }

  .err {
    margin: 0;
    font-size: var(--dm2-text-sm);
    color: var(--dm2-delete, #c4291c);
  }

  .ok-tip {
    font-size: var(--dm2-text-sm);
    color: var(--dm2-add, #1a8a3f);
    background: var(--dm2-add-weak, rgba(52, 199, 89, 0.08));
    border-radius: 6px;
    padding: 4px 8px;
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
