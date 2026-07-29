<template>
  <el-dialog v-model="visible" title="开始仿真 · 方案命名" width="560px" :close-on-click-modal="false" append-to-body>
    <div class="wizard">
      <div class="pipeline-tip">
        将按顺序执行：<b>① 按研究区域切分母本 → 生成基线模型</b> → <b>② 应用 {{ store.editCount }} 项修改 → 生成方案模型</b> → <b>③ 顺序运行两个模型</b>，完成后自动进入模型库，可在「运行监测 / 客流分析」中查看。
      </div>

      <!-- 校验 -->
      <div v-if="validating" class="validate-box">校验草稿中…</div>
      <div v-else-if="validationUnavailable" class="validate-box error">
        <b>校验服务暂不可用，为避免提交无效任务，已暂停开始仿真。</b>
        <el-button link type="primary" size="small" @click="validateDraft">重试校验</el-button>
      </div>
      <div v-else-if="validationErrors.length" class="validate-box error">
        <b>存在 {{ validationErrors.length }} 个错误，请先处理：</b>
        <div v-for="(issue, i) in visibleErrors" :key="i" class="issue">· {{ issue.message }}</div>
        <el-button v-if="validationErrors.length > 6" link size="small" @click="showAllIssues = !showAllIssues">
          {{ showAllIssues ? "收起" : `查看全部 ${validationErrors.length} 个错误` }}
        </el-button>
      </div>
      <div v-else-if="validationWarnings.length" class="validate-box warn">
        <b>{{ validationWarnings.length }} 个提示（可继续）：</b>
        <div v-for="(issue, i) in validationWarnings.slice(0, 4)" :key="i" class="issue">· {{ issue.message }}</div>
      </div>
      <div v-else class="validate-box ok">✓ 校验通过</div>

      <el-form label-width="96px" label-position="left" size="default">
        <el-form-item label="方案名称" required>
          <el-input v-model="form.schemeName" maxlength="40" placeholder="给本方案起个名字，如：金洲片区公交优化" />
        </el-form-item>
        <div v-if="form.schemeName.trim()" class="derive-tip">
          将生成两个模型：<b>{{ baselineName }}</b>（基线）与 <b>{{ variantName }}</b>（方案）
        </div>
        <el-form-item label="保存位置">
          <el-radio-group v-model="form.scope">
            <el-radio value="private">私有（仅自己可见）</el-radio>
            <el-radio value="public">公开（所有人可见）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="迭代次数">
          <el-input-number v-model="form.iterations" :min="10" :max="500" :step="10" />
          <span class="iter-tip">默认20次；可自行调整，区域小模型迭代较快</span>
        </el-form-item>
      </el-form>

      <div v-if="store.areaStats" class="resource-tip">
        研究区域 {{ store.areaStats.areaKm2 }} km² · 触达 {{ store.areaStats.lineTouchCount }} 条线路 · 区域内 {{ store.areaStats.stopCount }} 站
        <br />切分与两次仿真为后台任务，可关闭本窗口，在右侧「运行任务」中跟踪进度。
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="validating || validationUnavailable || validationErrors.length > 0 || !form.schemeName.trim()" @click="submit">
        开始仿真
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { optGenerate, optValidate } from "@/api/optimization";
import { useScenarioEditStore } from "../store";

const visible = defineModel({ type: Boolean, default: false });
const store = useScenarioEditStore();

const submitting = ref(false);
const validating = ref(false);
const validationIssues = ref([]);
const validationUnavailable = ref(false);
const showAllIssues = ref(false);

const validationErrors = computed(() => validationIssues.value.filter((i) => i.level === "error"));
const validationWarnings = computed(() => validationIssues.value.filter((i) => i.level === "warning"));
const visibleErrors = computed(() => showAllIssues.value ? validationErrors.value : validationErrors.value.slice(0, 6));

const form = reactive({
  schemeName: "",
  scope: "private",
  iterations: 20,
});

function dateSuffix() {
  const d = new Date();
  return `${String(d.getMonth() + 1).padStart(2, "0")}${String(d.getDate()).padStart(2, "0")}`;
}

// 方案名称 → 派生基线/方案两个模型名
const cleanName = computed(() => form.schemeName.trim().replace(/[/\\.]/g, "") || "方案");
const baselineName = computed(() => `${cleanName.value}-基线-${dateSuffix()}`);
const variantName = computed(() => `${cleanName.value}-方案-${dateSuffix()}`);

async function validateDraft() {
  validating.value = true;
  validationUnavailable.value = false;
  validationIssues.value = [];
  try {
    const saved = await store.saveDraftNow();
    if (!saved) throw new Error("草稿保存失败");
    const res = await optValidate({ parentModel: store.parentModel, draftId: store.draft.draftId });
    validationIssues.value = Array.isArray(res?.data) ? res.data : [];
  } catch (e) {
    validationUnavailable.value = true;
  } finally {
    validating.value = false;
  }
}

watch(visible, async (v) => {
  if (!v) return;
  form.schemeName = store.draft.name && store.draft.name !== "未命名方案" ? store.draft.name : "";
  showAllIssues.value = false;
  await validateDraft();
});

async function submit() {
  if (!form.schemeName.trim() || validationUnavailable.value || validationErrors.value.length) return;
  if (form.scope === "public") {
    try {
      await ElMessageBox.confirm("公开模型对所有平台用户可见。确定继续？", "确认公开范围", {
        confirmButtonText: "确认公开并开始", cancelButtonText: "取消", type: "warning",
      });
    } catch { return; }
  }
  submitting.value = true;
  try {
    // 命名落到草稿：左侧标识与后续二次生成沿用
    store.draft.name = form.schemeName.trim();
    const saved = await store.saveDraftNow();
    if (!saved) {
      ElMessage.error("草稿保存失败，已取消提交");
      return;
    }
    const res = await optGenerate({
      draftId: store.draft.draftId,
      parentModel: store.parentModel,
      baselineName: baselineName.value,
      variantName: variantName.value,
      scope: form.scope === "public" ? "public" : "",
      iterations: form.iterations,
    });
    if (res?.data?.jobId) {
      ElMessage.success("任务已提交，正在后台执行");
      store.startJobPolling();
      visible.value = false;
    }
  } catch (e) {
    /* request 拦截器已提示 */
  } finally {
    submitting.value = false;
  }
}
</script>

<style lang="scss" scoped>
.wizard {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.pipeline-tip {
  font-size: 12px;
  line-height: 1.7;
  color: var(--app-ink-weak, #475569);
  background: rgba(21, 105, 222, 0.06);
  border-radius: 8px;
  padding: 8px 10px;
}

.validate-box {
  font-size: 12px;
  border-radius: 8px;
  padding: 8px 10px;
  line-height: 1.6;

  &.ok { background: rgba(15, 159, 110, 0.08); color: #0f9f6e; }
  &.warn { background: rgba(245, 158, 11, 0.1); color: #b45309; }
  &.error { background: rgba(220, 38, 38, 0.08); color: #b91c1c; }

  .issue { margin-top: 2px; }
}

.derive-tip {
  margin: -4px 0 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-ink-weak, #64748b);

  b { color: var(--app-blue, #1569de); font-weight: 600; }
}

.iter-tip {
  margin-left: 10px;
  font-size: 12px;
  color: #94a3b8;
}

.resource-tip {
  font-size: 12px;
  color: var(--app-ink-weak, #64748b);
  line-height: 1.7;
  border-top: 1px dashed var(--app-border, #e2e8f0);
  padding-top: 8px;
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
html.dark .pipeline-tip {
  color: #c2cddd;
  background: rgba(64, 156, 255, 0.1);
}

html.dark .validate-box.ok {
  background: rgba(76, 205, 118, 0.12);
  color: #4ccd76;
}
html.dark .validate-box.warn {
  background: rgba(234, 176, 76, 0.12);
  color: #eab04c;
}
html.dark .validate-box.error {
  background: rgba(255, 122, 110, 0.12);
  color: #ff7a6e;
}

html.dark .derive-tip {
  color: #94a3b8;
}

html.dark .resource-tip {
  color: #94a3b8;
}
</style>
