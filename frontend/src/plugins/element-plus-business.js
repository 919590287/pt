import "@/assets/styles/element.scss";
import {
  ElAutoResizer,
  ElButtonGroup,
  ElCheckbox,
  ElCheckboxGroup,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElIcon,
  ElInputNumber,
  ElOption,
  ElOptionGroup,
  ElPagination,
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
  ElTable,
  ElTableColumn,
  ElTimeSelect,
} from "element-plus";

const components = [
  ElAutoResizer,
  ElButtonGroup,
  ElCheckbox,
  ElCheckboxGroup,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElIcon,
  ElInputNumber,
  ElOption,
  ElOptionGroup,
  ElPagination,
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
  ElTable,
  ElTableColumn,
  ElTimeSelect,
];

const installedApps = new WeakSet();

export function installBusinessElementPlus(app) {
  if (installedApps.has(app)) return;
  components.forEach((component) => app.use(component));
  installedApps.add(app);
}
