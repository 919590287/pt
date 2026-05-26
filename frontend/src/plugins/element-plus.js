import {
  ElAutoResizer,
  ElButton,
  ElButtonGroup,
  ElCheckbox,
  ElEmpty,
  ElIcon,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElMessageBox,
  ElNotification,
  ElOption,
  ElOptionGroup,
  ElPopover,
  ElProgress,
  ElRadio,
  ElRadioButton,
  ElRadioGroup,
  ElScrollbar,
  ElSelect,
  ElSelectV2,
  ElSkeleton,
  ElSlider,
  ElSwitch,
  ElTag,
  provideGlobalConfig,
} from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";

const components = [
  ElAutoResizer,
  ElButton,
  ElButtonGroup,
  ElCheckbox,
  ElEmpty,
  ElIcon,
  ElInput,
  ElInputNumber,
  ElOption,
  ElOptionGroup,
  ElPopover,
  ElProgress,
  ElRadio,
  ElRadioButton,
  ElRadioGroup,
  ElScrollbar,
  ElSelect,
  ElSelectV2,
  ElSkeleton,
  ElSlider,
  ElSwitch,
  ElTag,
];

export function installElementPlus(app) {
  provideGlobalConfig({ locale: zhCn }, app, true);
  components.forEach((component) => app.use(component));
}

export { ElMessage, ElMessageBox, ElNotification };
