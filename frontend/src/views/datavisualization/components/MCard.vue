<!-- MCard -->
<template>
  <div class="MCard">
    <div class="MCard_title_box" @click="handleSetOpen(!s_open)">
      <slot v-if="$slots.title" name="title" class="MCard_title"></slot>
      <div class="MCard_title" v-else>{{ title }}</div>
      <el-icon class="MCard_open_btn" :class="{ open: s_open }"><ArrowDownBold /></el-icon>
    </div>
    <!-- <transition name="el-zoom-in-top" :duration="100"> -->
    <div class="MCard_body_box" :class="wrapBodyClass" v-show="s_open">
      <slot name="body"></slot>
    </div>
    <!-- </transition> -->
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
  wrapBodyClass: {
    type: String,
    default: "",
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
.MCard {
  --border-color: #d9d9d9;

  overflow: hidden;
  border: 1px solid var(--border-color);
  border-radius: var(--el-border-radius-base);
  background-color: var(--el-color-white);

  .MCard_title_box {
    cursor: pointer;
    display: flex;
    padding: 5px 10px;
    gap: 10px;
    align-items: center;
    line-height: 30px;
    background-color: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
    .MCard_title {
      width: 0;
      flex: 1;
      font-weight: bold;
    }
    .MCard_open_btn {
      cursor: pointer;
      transition: transform 0.3s;
      &.open {
        transform: rotate(180deg);
      }
    }
  }
  .MCard_body_box {
    padding: 5px 10px;
    border-top: 1px solid var(--border-color);
  }
}
</style>
