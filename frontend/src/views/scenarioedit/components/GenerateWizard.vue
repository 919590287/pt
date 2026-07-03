<template>
  <el-dialog v-model="visible" title="生成仿真模型" width="560px" :close-on-click-modal="false" append-to-body>
    <div class="wizard">
      <div class="pipeline-tip">
        将按顺序执行：<b>① 按研究区域切分母本 → 生成基线模型</b> → <b>② 应用 {{ store.editCount }} 项修改 → 生成方案模型</b> → <b>③ 顺序运行两个模型</b>，完成后自动进入模型库，可在「运行监测 / 客流分析」中查看。
      </div>

      <!-- 校验 -->
      <div v-if="validating" class="validate-box">校验草稿中…</div>
      <div v-else-if="validationErrors.length" class="validate-box error">
        <b>存在 {{ validationErrors.length }} 个错误，请先处理：</b>
        <div v-for="(issue, i) in validationErrors.slice(0, 6)" :key="i" class="issue">· {{ issue.message }}</div>
      </div>
      <div v-else-if="validationWarnings.length" class="validate-box warn">
        <b>{{ validationWarnings.length }} 个提示（可继续）：</b>
        <div v-for="(issue, i) in validationWarnings.slice(0, 4)" :key="i" class="issue">· {{ issue.message }}</div>
      </div>
      <div v-else class="validate-box ok">✓ 校验通过</div>

      <el-form label-width="96px" label-position="left" size="default">
        <el-form-item label="基线模型名">
          <el-input v-model="form.baselineName" maxlength="60" />
        </el-form-item>
        <el-form-item label="方案模型名">
          <el-input v-model="form.variantName" maxlength="60" />
        </el-form-item>
        <el-form-item label="保存位置">
          <el-radio-group v-model="form.scope">
            <el-radio value="private">私有（仅自己可见）</el-radio>
            <el-radio value="public">公开（所有人可见）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="迭代次数">
          <el-input-number v-model="form.iterations" :min="10" :max="500" :step="10" />
          <span class="iter-tip">默认100次；区域小模型迭代较快</span>
        </el-form-item>
      </el-form>

      <div v-if="store.areaStats" class="resource-tip">
        研究区域 {{ store.areaStats.areaKm2 }} km² · 触达 {{ store.areaStats.lineTouchCount }} 条线路 · 区域内 {{ store.areaStats.stopCount }} 站
        <br />切分与两次仿真为后台任务，可关闭本窗口，在右侧「运行任务」中跟踪进度。
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="validating || validationErrors.length > 0 || !form.baselineName || !form.variantName" @click="submit">
        开始生成并运行
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { optGenerate, optValidate } from "@/api/optimization";
import { useScenarioEditStore } from "../store";

const visible = defineModel({ type: Boolean, default: false });
const store = useScenarioEditStore();

const submitting = ref(false);
const validating = ref(false);
const validationIssues = ref([]);

const validationErrors = computed(() => validationIssues.value.filter((i) => i.level === "error"));
const validationWarnings = computed(() => validationIssues.value.filter((i) => i.level === "warning"));

const form = reactive({
  baselineName: "",
  variantName: "",
  scope: "private",
  iterations: 100,
});

function dateSuffix() {
  const d = new Date();
  return `${String(d.getMonth() + 1).padStart(2, "0")}${String(d.getDate()).padStart(2, "0")}`;
}

watch(visible, async (v) => {
  if (!v) return;
  const base = (store.draft.name || "方案").replace(/[/\\.]/g, "");
  form.baselineName = `${base}-基线-${dateSuffix()}`;
  form.variantName = `${base}-方案-${dateSuffix()}`;
  // 确保草稿已落库再校验
  await store.saveDraftNow();
  validating.value = true;
  validationIssues.value = [];
  try {
    const res = await optValidate({ parentModel: store.parentModel, draftId: store.draft.draftId });
    validationIssues.value = Array.isArray(res?.data) ? res.data : [];
  } catch (e) {
    validationIssues.value = [{ level: "warning", message: "校验服务暂不可用，可继续提交（生成阶段仍会全量校验）" }];
  } finally {
    validating.value = false;
  }
});

async function submit() {
  submitting.value = true;
  try {
    await store.saveDraftNow();
    const res = await optGenerate({
      draftId: store.draft.draftId,
      parentModel: store.parentModel,
      baselineName: form.baselineName.trim(),
      variantName: form.variantName.trim(),
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
</style>
