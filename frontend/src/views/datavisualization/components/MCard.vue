<!-- MCard -->
<template>
  <div class="MCard">
    <button class="MCard_title_box" type="button" :aria-expanded="s_open" @click="handleSetOpen(!s_open)">
      <slot v-if="$slots.title" name="title" class="MCard_title"></slot>
      <div class="MCard_title" v-else>{{ title }}</div>
      <el-icon class="MCard_open_btn" :class="{ open: s_open }"><ArrowDownBold /></el-icon>
    </button>
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
  background: var(--app-card-bg-tint);
  box-shadow: 0 1px 0 rgba(21, 105, 222, 0.04);

  .MCard_title_box {
    width: 100%;
    border: 0;
    cursor: pointer;
    display: flex;
    padding: var(--space-xs) var(--space-sm);
    gap: var(--space-xs);
    align-items: center;
    text-align: left;
    min-height: 36px;
    background: rgba(21, 105, 222, 0.07);
    color: var(--app-blue);
    transition: background-color var(--app-motion-normal) var(--app-ease-out);

    &:hover {
      background: rgba(21, 105, 222, 0.085);
    }

    .MCard_title {
      width: 0;
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-weight: 750;
      letter-spacing: 0;
    }
    .MCard_open_btn {
      cursor: pointer;
      transition: transform var(--app-motion-slow) var(--app-ease-out);
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
