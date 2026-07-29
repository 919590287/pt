import {
  ElButton,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  provideGlobalConfig,
} from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";

const authComponents = [ElButton, ElForm, ElFormItem, ElInput];
let installedApp = null;
let businessInstallPromise = null;

export function installElementPlus(app) {
  installedApp = app;
  provideGlobalConfig({ locale: zhCn }, app, true);
  authComponents.forEach((component) => app.use(component));
}

export function ensureBusinessElementPlus() {
  if (!installedApp) return Promise.resolve();
  if (!businessInstallPromise) {
    businessInstallPromise = import("./element-plus-business.js")
      .then(({ installBusinessElementPlus }) => installBusinessElementPlus(installedApp))
      .catch((error) => {
        businessInstallPromise = null;
        throw error;
      });
  }
  return businessInstallPromise;
}

export { ElMessage };
