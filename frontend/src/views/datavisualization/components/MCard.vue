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
  --border-color: rgba(21, 105, 222, 0.12);

  overflow: hidden;
  border: 1px solid var(--border-color);
  border-radius: var(--app-card-radius);
  background-color: rgba(255, 255, 255, 0.94);
  box-shadow: var(--app-shadow-sm);

  .MCard_title_box {
    cursor: pointer;
    display: flex;
    padding: var(--space-xs) var(--space-sm);
    gap: var(--space-xs);
    align-items: center;
    min-height: 36px;
    background: linear-gradient(180deg, rgba(21, 105, 222, 0.09) 0%, rgba(21, 105, 222, 0.035) 100%);
    color: var(--app-blue);
    .MCard_title {
      width: 0;
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-weight: 700;
      letter-spacing: 0;
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
    padding: var(--space-sm);
    border-top: 1px solid var(--border-color);
  }
}
</style>
