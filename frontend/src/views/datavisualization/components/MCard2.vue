<!-- MCard2 -->
<template>
  <div class="MCard2">
    <div class="MCard2_title_box" @click="handleSetOpen(!s_open)">
      <slot v-if="$slots.title" name="title" class="MCard2_title"></slot>
      <div class="MCard2_title" v-else>{{ title }}</div>
      <el-icon class="MCard2_open_btn" :class="{ open: s_open }"><ArrowDownBold /></el-icon>
    </div>
    <div class="MCard2_body_box" v-show="s_open">
      <slot name="body"></slot>
    </div>
  </div>
</template>

<script setup>
import { ArrowDownBold } from "@element-plus/icons-vue";
const props = defineProps({
  title: {
    type: String,
    default: "&nbsp;",
  },
  open: {
    type: Boolean,
    default: true,
  },
});
const emits = defineEmits(["update:open"]);

watch(
  () => props.open,
  (open) => handleSetOpen(open),
);
const s_open = ref(props.open);
function handleSetOpen(open) {
  s_open.value = open;
  emits("update:open", open);
}
</script>

<style lang="scss">
.MCard2 {
  --border-color: #d9d9d9;

  overflow: hidden;
  border-radius: var(--el-border-radius-base);
  background-color: var(--el-color-white);

  .MCard2_title_box {
    cursor: pointer;
    display: flex;
    padding: 5px 10px;
    gap: 10px;
    align-items: center;
    line-height: 30px;
    background: linear-gradient(to bottom, rgb(from var(--el-color-primary-light-7) r g b / 0.5) 0, rgb(from var(--el-color-primary-light-7) r g b / 0.2) 100%);
    color: var(--el-color-primary);

    &::before {
      content: "";
      display: block;
      width: 3px;
      height: 16px;
      border-radius: 2px;
      background-color: var(--el-color-primary);
    }

    .MCard2_title {
      white-space: nowrap;
      margin-right: auto;
      font-weight: bold;
    }
    .MCard2_open_btn {
      cursor: pointer;
      transition: transform 0.3s;
      &.open {
        transform: rotate(180deg);
      }
    }
  }
  .MCard2_body_box {
    padding: 5px 10px;
    border-top: 1px solid var(--border-color);
  }
}
</style>
