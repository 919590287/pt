<template>
  <div class="run-tasks">
    <div class="head">
      <span class="title">运行任务</span>
      <el-button link size="small" @click="store.refreshJobs()">刷新</el-button>
    </div>
    <div v-if="store.jobsError" class="empty" role="alert">{{ store.jobsError }}</div>
    <div v-if="!store.jobs.length" class="empty">暂无任务</div>
    <div v-for="job in store.jobs" :key="job.jobId" class="job-card" :class="job.stage">
      <div class="job-head">
        <span class="name">{{ job.draftName || job.jobId }}</span>
        <el-tag size="small" :type="stageTag(job)" effect="plain">{{ stageLabel(job) }}</el-tag>
      </div>
      <div class="stages">
        <span v-for="(s, i) in STAGES" :key="s.key" :class="['stage-dot', stageState(job, s.key)]" :title="s.label">
          {{ s.short }}<span v-if="i < STAGES.length - 1" class="arrow">›</span>
        </span>
      </div>
      <el-progress :percentage="job.percent" :stroke-width="6" :status="progressStatus(job)" :show-text="false" />
      <div class="msg">{{ job.message }}</div>
      <div v-if="isRunningStage(job)" class="iter">迭代 {{ job.iteration }}/{{ job.lastIteration }}</div>
      <div class="ops">
        <el-button v-if="!isTerminal(job)" link size="small" type="danger" @click="cancel(job)">取消</el-button>
        <el-button v-if="job.stage === 'failed' || job.stage === 'canceled'" link size="small" type="primary" @click="retry(job)">重试</el-button>
        <el-button v-if="isTerminal(job)" link size="small" @click="cleanup(job)">移除</el-button>
        <el-button v-if="job.logTail?.length" link size="small" @click="toggleLog(job.jobId)">
          {{ expandedLog === job.jobId ? "收起日志" : "日志" }}
        </el-button>
        <el-button v-if="job.stage === 'done'" link size="small" type="success" @click="goView(job)">去查看</el-button>
      </div>
      <div v-if="job.validationIssues?.length && job.stage === 'failed'" class="issues">
        <div v-for="(issue, i) in job.validationIssues.filter((x) => x.level === 'error').slice(0, 4)" :key="i">· {{ issue.message }}</div>
      </div>
      <pre v-if="expandedLog === job.jobId" class="log">{{ (job.logTail || []).join("\n") }}</pre>
    </div>
  </div>
</template>

<script setup>
import { ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { optJobCancel, optJobRetry, optJobCleanup } from "@/api/optimization";
import { useScenarioEditStore } from "../store";

const store = useScenarioEditStore();
const router = useRouter();
const expandedLog = ref("");

const STAGES = [
  { key: "cut", label: "切分", short: "切分" },
  { key: "apply", label: "应用修改", short: "应用" },
  { key: "validate", label: "校验", short: "校验" },
  { key: "runBaseline", label: "运行基线", short: "基线" },
  { key: "runVariant", label: "运行方案", short: "方案" },
  { key: "register", label: "注册", short: "注册" },
];

const ORDER = ["queued", "cut", "apply", "validate", "runBaseline", "runVariant", "register", "done"];

function stageState(job, key) {
  const cur = ORDER.indexOf(job.stage);
  const idx = ORDER.indexOf(key);
  if (job.stage === "done") return "done";
  if (cur < 0) return job.percent >= 100 ? "done" : "todo"; // failed/canceled 保持最后位置
  if (idx < cur) return "done";
  if (idx === cur) return "doing";
  return "todo";
}

function stageLabel(job) {
  const map = {
    queued: "排队中", cut: "切分中", apply: "应用修改", validate: "校验中",
    runBaseline: "运行基线", runVariant: "运行方案", register: "注册中",
    done: "完成", failed: "失败", canceled: "已取消",
  };
  return map[job.stage] || job.stage;
}

function stageTag(job) {
  if (job.stage === "done") return "success";
  if (job.stage === "failed") return "danger";
  if (job.stage === "canceled") return "info";
  return "primary";
}

function progressStatus(job) {
  if (job.stage === "done") return "success";
  if (job.stage === "failed") return "exception";
  return "";
}

function isTerminal(job) {
  return ["done", "failed", "canceled"].includes(job.stage);
}

function isRunningStage(job) {
  return job.stage === "runBaseline" || job.stage === "runVariant";
}

function toggleLog(jobId) {
  expandedLog.value = expandedLog.value === jobId ? "" : jobId;
}

async function cancel(job) {
  try {
    await ElMessageBox.confirm("确定取消该任务？已完成的运行结果将不会注册。", "取消任务", { type: "warning" });
  } catch {
    return;
  }
  await optJobCancel({ jobId: job.jobId });
  store.refreshJobs();
}

async function retry(job) {
  try {
    await ElMessageBox.confirm(`将重新提交「${job.draftName || job.jobId}」，并再次执行切分与两次仿真。`, "重试任务", {
      confirmButtonText: "确认重试", cancelButtonText: "取消", type: "warning",
    });
  } catch { return; }
  await optJobRetry({ jobId: job.jobId });
  ElMessage.success("已重新提交");
  store.refreshJobs();
}

async function cleanup(job) {
  try {
    await ElMessageBox.confirm("将从任务列表移除该记录，已注册的模型不会被删除。", "移除任务记录", {
      confirmButtonText: "移除记录", cancelButtonText: "取消", type: "warning",
    });
  } catch { return; }
  await optJobCleanup({ jobId: job.jobId });
  store.refreshJobs();
}

function goView(job) {
  ElMessage.success(`模型「${job.baselineName}」「${job.variantName}」已在模型库，右上角切换模型即可查看`);
  router.push({ name: "datavisualization" });
}
</script>

<style lang="scss" scoped>
.run-tasks {
  display: flex;
  flex-direction: column;
  gap: 8px;

  .head {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .title {
      font-size: var(--dm2-text-base);
      font-weight: var(--dm2-fw-bold);
    }
  }

  .empty {
    font-size: var(--dm2-text-sm);
    color: var(--dm2-muted, #667085);
    text-align: center;
    padding: var(--dm2-space-2);
  }
}

.job-card {
  border: 1px solid var(--dm2-line, #e8edf5);
  border-radius: var(--dm2-radius-sm);
  padding: var(--dm2-space-2) var(--dm2-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-2);
  background: var(--dm2-surface, #fff);

  &.failed { border-color: var(--dm2-delete-line, rgba(220, 38, 38, 0.35)); }
  &.done { border-color: var(--dm2-add-line, rgba(15, 159, 110, 0.35)); }

  .job-head {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .name {
      font-size: var(--dm2-text-sm);
      font-weight: var(--dm2-fw-bold);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .stages {
    display: flex;
    flex-wrap: wrap;
    gap: 2px;
    font-size: var(--dm2-text-xs);

    .stage-dot {
      color: var(--dm2-muted-soft, #98a2b3);

      &.done { color: var(--dm2-add, #1a8a3f); }
      &.doing { color: var(--dm2-accent, #0071e3); font-weight: var(--dm2-fw-bold); }

      .arrow { margin: 0 3px; color: var(--dm2-muted-soft, #98a2b3); }
    }
  }

  .msg {
    font-size: var(--dm2-text-xs);
    color: var(--dm2-muted, #667085);
    line-height: 1.5;
    word-break: break-all;
  }

  .iter {
    font-size: var(--dm2-text-xs);
    color: var(--dm2-accent, #0071e3);
    font-weight: var(--dm2-fw-semibold);
  }

  .ops {
    display: flex;
    gap: 2px;
  }

  .issues {
    font-size: var(--dm2-text-xs);
    color: var(--dm2-delete, #c4291c);
    line-height: 1.6;
  }

  .log {
    max-height: 160px;
    overflow: auto;
    background: #0f172a;
    color: #cbd5e1;
    font-size: 10px;
    line-height: 1.5;
    border-radius: 6px;
    padding: 6px 8px;
    margin: 0;
    white-space: pre-wrap;
    word-break: break-all;
  }
}
</style>
