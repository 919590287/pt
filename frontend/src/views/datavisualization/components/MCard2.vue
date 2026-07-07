<!-- MCard2 -->
<template>
  <div class="MCard2">
    <button class="MCard2_title_box" type="button" :aria-expanded="s_open" @click="handleSetOpen(!s_open)">
      <slot v-if="$slots.title" name="title" class="MCard2_title"></slot>
      <div class="MCard2_title" v-else>{{ title }}</div>
      <el-icon class="MCard2_open_btn" :class="{ open: s_open }"><ArrowDownBold /></el-icon>
    </button>
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
    default: "",
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
  --border-color: rgba(21, 105, 222, 0.12);

  overflow: hidden;
  border: 1px solid var(--border-color);
  border-radius: var(--app-card-radius);
  background: var(--app-card-bg-tint);
  box-shadow: var(--app-shadow-sm);

  .MCard2_title_box {
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

    .MCard2_title {
      white-space: nowrap;
      margin-right: auto;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      font-weight: 750;
      letter-spacing: 0;
    }
    .MCard2_open_btn {
      cursor: pointer;
      transition: transform var(--app-motion-slow) var(--app-ease-out);
      &.open {
        transform: rotate(180deg);
      }
    }
  }
  .MCard2_body_box {
    padding: var(--space-sm);
    border-top: 1px solid var(--border-color);
  }
}
</style>
