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
  --border-color: rgba(21, 105, 222, 0.12);

  overflow: hidden;
  border: 1px solid var(--border-color);
  border-radius: var(--app-card-radius);
  background-color: rgba(255, 255, 255, 0.94);
  box-shadow: var(--app-shadow-sm);

  .MCard2_title_box {
    cursor: pointer;
    display: flex;
    padding: var(--space-xs) var(--space-sm);
    gap: var(--space-xs);
    align-items: center;
    min-height: 36px;
    background: linear-gradient(180deg, rgba(21, 105, 222, 0.09) 0%, rgba(21, 105, 222, 0.035) 100%);
    color: var(--app-blue);

    .MCard2_title {
      white-space: nowrap;
      margin-right: auto;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      font-weight: 700;
      letter-spacing: 0;
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
    padding: var(--space-sm);
    border-top: 1px solid var(--border-color);
  }
}
</style>
