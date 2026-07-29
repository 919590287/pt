<template>
  <main class="auth-page">
    <section class="auth-panel" aria-label="用户认证">
      <div class="brand-block">
        <div class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 36 36" width="36" height="36" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect width="36" height="36" rx="9" fill="url(#auth-logo-gradient)" />
            <path d="M10 18 C 14 13, 22 13, 26 18" stroke="#f8fbff" stroke-width="2.5" stroke-linecap="round" opacity="0.85" />
            <path d="M10 18 C 14 23, 22 23, 26 18" stroke="#31d8ee" stroke-width="2" stroke-linecap="round" opacity="0.95" />
            <circle cx="10" cy="18" r="3.5" fill="#f8fbff" />
            <circle cx="18" cy="15" r="3" fill="#8aa8ff" />
            <circle cx="26" cy="18" r="3.5" fill="#31d8ee" />
            <defs>
              <linearGradient id="auth-logo-gradient" x1="0" y1="0" x2="36" y2="36" gradientUnits="userSpaceOnUse">
                <stop offset="0%" stop-color="#1569de" />
                <stop offset="52%" stop-color="#0b91b7" />
                <stop offset="100%" stop-color="#31d8ee" />
              </linearGradient>
            </defs>
          </svg>
        </div>
        <div>
          <p class="eyebrow">公共交通数智化治理平台</p>
          <h1>{{ pageTitle }}</h1>
        </div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="auth-form" @keyup.enter="handleSubmit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model.trim="form.username" autocomplete="username" maxlength="32" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item :label="passwordLabel" prop="password">
          <el-input v-model="form.password" type="password" autocomplete="current-password" maxlength="64" :placeholder="passwordPlaceholder" show-password />
        </el-form-item>
        <el-form-item v-if="mode === 'resetPassword'" label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" autocomplete="new-password" maxlength="64" placeholder="请输入新密码" show-password />
        </el-form-item>
        <el-form-item v-if="needsConfirm" label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" autocomplete="new-password" maxlength="64" placeholder="请再次输入密码" show-password />
        </el-form-item>

        <el-button type="primary" class="submit-btn" :loading="submitting" @click="handleSubmit">
          {{ submitText }}
        </el-button>
      </el-form>

      <div class="auth-links">
        <RouterLink v-if="mode !== 'login'" :to="{ name: 'login' }">返回登录</RouterLink>
        <template v-else>
          <RouterLink :to="{ name: 'register' }">注册账户</RouterLink>
          <RouterLink :to="{ name: 'resetPassword' }">修改密码</RouterLink>
        </template>
      </div>
    </section>
  </main>
</template>

<script setup>
import { ElMessage } from "element-plus";
import { login, register, resetPassword } from "@/api/auth";
import { saveAuth } from "@/utils/auth";

defineOptions({
  name: "AuthView",
});

const route = useRoute();
const router = useRouter();
const formRef = ref(null);
const submitting = ref(false);
const form = reactive({
  username: "",
  password: "",
  newPassword: "",
  confirmPassword: "",
});

const mode = computed(() => {
  if (route.name === "register") return "register";
  if (route.name === "resetPassword") return "resetPassword";
  return "login";
});
const needsConfirm = computed(() => mode.value !== "login");
const pageTitle = computed(() => {
  if (mode.value === "register") return "注册账户";
  if (mode.value === "resetPassword") return "修改密码";
  return "登录后进入系统";
});
const passwordLabel = computed(() => (mode.value === "resetPassword" ? "原密码" : "密码"));
const passwordPlaceholder = computed(() => (mode.value === "resetPassword" ? "请输入原密码" : "请输入密码"));
const submitText = computed(() => {
  if (mode.value === "register") return "注册并进入系统";
  if (mode.value === "resetPassword") return "修改并进入系统";
  return "登录";
});

const validateConfirm = (rule, value, callback) => {
  if (!needsConfirm.value) {
    callback();
    return;
  }
  if (!value) {
    callback(new Error("请确认密码"));
    return;
  }
  const expected = mode.value === "resetPassword" ? form.newPassword : form.password;
  if (value !== expected) {
    callback(new Error("两次输入的密码不一致"));
    return;
  }
  callback();
};

const rules = computed(() => ({
  username: [
    { required: true, message: "请输入用户名", trigger: "blur" },
    { pattern: /^[\p{L}\p{N}_.-]{2,32}$/u, message: "用户名需为2-32位中文、字母、数字、点、短横线或下划线", trigger: "blur" },
  ],
  password: [
    { required: true, message: mode.value === "resetPassword" ? "请输入原密码" : "请输入密码", trigger: "blur" },
    { min: 6, max: 64, message: "密码长度需为6-64位", trigger: "blur" },
  ],
  newPassword: mode.value === "resetPassword"
    ? [
        { required: true, message: "请输入新密码", trigger: "blur" },
        { min: 6, max: 64, message: "密码长度需为6-64位", trigger: "blur" },
      ]
    : [],
  confirmPassword: [{ validator: validateConfirm, trigger: "blur" }],
}));

watch(mode, () => {
  form.password = "";
  form.newPassword = "";
  form.confirmPassword = "";
  nextTick(() => formRef.value?.clearValidate?.());
});

async function handleSubmit() {
  if (submitting.value) return;
  try {
    await formRef.value?.validate?.();
  } catch (error) {
    return;
  }
  submitting.value = true;
  try {
    const payload = {
      username: form.username,
      password: form.password,
      newPassword: mode.value === "resetPassword" ? form.newPassword : form.password,
    };
    const request = mode.value === "register" ? register : mode.value === "resetPassword" ? resetPassword : login;
    const res = await request(payload);
    saveAuth(res.data);
    ElMessage.success(mode.value === "login" ? "登录成功" : "账户已更新");
    router.replace(route.query.redirect || { name: "datavisualization" });
  } catch (error) {
    // request.js has already shown the user-facing message.
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped lang="scss">
.auth-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 32px;
  color: #10243f;
  background:
    repeating-linear-gradient(90deg, rgba(21, 105, 222, 0.05) 0 1px, transparent 1px 72px),
    repeating-linear-gradient(0deg, rgba(11, 145, 183, 0.04) 0 1px, transparent 1px 72px),
    linear-gradient(145deg, #eef5fd 0%, #f8fbff 48%, #e8f2f7 100%);
}

.auth-panel {
  width: min(420px, 100%);
  padding: 34px 34px 28px;
  border: 1px solid rgba(69, 112, 158, 0.18);
  border-radius: 8px;
  background: rgba(248, 251, 255, 0.94);
  box-shadow: 0 22px 64px rgba(29, 72, 119, 0.16);
}

.brand-block {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 28px;
}

.brand-mark {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: #edf6fb;
}

.eyebrow {
  margin: 0 0 4px;
  color: #58728d;
  font-size: 0.82rem;
  font-weight: 650;
}

h1 {
  margin: 0;
  font-size: 1.35rem;
  line-height: 1.25;
  font-weight: 760;
  color: #10243f;
  letter-spacing: 0;
}

.auth-form {
  :deep(.el-form-item__label) {
    color: #294761;
    font-weight: 650;
  }

  :deep(.el-input__wrapper) {
    min-height: 42px;
    border-radius: 7px;
    background: #fbfdff;
    box-shadow: 0 0 0 1px rgba(69, 112, 158, 0.2) inset;
  }
}

.submit-btn {
  width: 100%;
  min-height: 42px;
  margin-top: 4px;
  border-radius: 7px;
  font-weight: 700;
}

.auth-links {
  display: flex;
  justify-content: center;
  gap: 18px;
  margin-top: 20px;

  a {
    color: #1569de;
    font-size: 0.92rem;
    font-weight: 650;
    text-decoration: none;

    &:hover {
      color: #0b91b7;
    }
  }
}

@media (max-width: 520px) {
  .auth-page {
    padding: 18px;
  }

  .auth-panel {
    padding: 28px 22px 24px;
  }
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
/* 持久化暗底图的用户刷新后登录页也处于 html.dark；保持网格+渐变构图，仅翻转明度 */
html.dark .auth-page {
  color: #e7edf6;
  background:
    repeating-linear-gradient(90deg, rgba(64, 156, 255, 0.09) 0 1px, transparent 1px 72px),
    repeating-linear-gradient(0deg, rgba(49, 216, 238, 0.08) 0 1px, transparent 1px 72px),
    linear-gradient(145deg, #10161e 0%, #0d1218 48%, #10161e 100%);
}

html.dark .auth-panel {
  border-color: rgba(148, 180, 220, 0.16);
  background: rgba(16, 22, 30, 0.94);
  box-shadow: 0 22px 64px rgba(2, 6, 12, 0.4);
}

html.dark .brand-mark {
  background: #1a2431;
}

html.dark .eyebrow {
  color: #94a3b8;
}

html.dark h1 {
  color: #e7edf6;
}

html.dark .auth-form :deep(.el-form-item__label) {
  color: #c2cddd;
}

html.dark .auth-form :deep(.el-input__wrapper) {
  background: #1a2431;
  box-shadow: 0 0 0 1px rgba(148, 180, 220, 0.28) inset;
}

html.dark .auth-links a {
  color: #409cff;
}
html.dark .auth-links a:hover {
  color: #31d8ee;
}
</style>
