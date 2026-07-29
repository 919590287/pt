<template>
  <Teleport to="body">
    <div
      v-if="active || preferenceVisible"
      class="rm-tour-layer"
      :aria-label="active ? '运行监测新手引导' : '新手引导显示偏好'"
    >
      <template v-if="active">
        <div class="rm-tour-mask rm-tour-mask-top" :style="maskStyles.top" @pointerdown.stop.prevent @click.stop.prevent></div>
        <div class="rm-tour-mask rm-tour-mask-left" :style="maskStyles.left" @pointerdown.stop.prevent @click.stop.prevent></div>
        <div class="rm-tour-mask rm-tour-mask-right" :style="maskStyles.right" @pointerdown.stop.prevent @click.stop.prevent></div>
        <div class="rm-tour-mask rm-tour-mask-bottom" :style="maskStyles.bottom" @pointerdown.stop.prevent @click.stop.prevent></div>

        <div class="rm-tour-spotlight" :style="spotlightStyle" aria-hidden="true"></div>

        <Transition name="rm-tour-bubble" mode="out-in">
          <section
            :key="currentStep?.id"
            ref="tooltipRef"
            class="rm-tour-bubble"
            :class="`is-${placement}`"
            :style="tooltipStyle"
            role="dialog"
            aria-modal="true"
            :aria-labelledby="`rm-tour-title-${currentStep?.id}`"
            :aria-describedby="`rm-tour-desc-${currentStep?.id}`"
            tabindex="-1"
            @click.stop
          >
            <span class="rm-tour-eyebrow">运行监测导览</span>
            <h2 :id="`rm-tour-title-${currentStep?.id}`">{{ currentStep?.title }}</h2>
            <p :id="`rm-tour-desc-${currentStep?.id}`">{{ currentStep?.description }}</p>

            <div class="rm-tour-progress" aria-hidden="true">
              <span :style="{ width: `${progressPercent}%` }"></span>
            </div>
            <div class="rm-tour-progress-text" aria-live="polite">
              步骤 {{ currentIndex + 1 }} / {{ steps.length }}
            </div>

            <div class="rm-tour-actions">
              <button type="button" class="rm-tour-skip" @click="requestExit">跳过</button>
              <div class="rm-tour-nav-actions">
                <button type="button" class="rm-tour-secondary" :disabled="currentIndex === 0" @click="previousStep">上一步</button>
                <button type="button" class="rm-tour-primary" @click="nextStep">
                  {{ isLastStep ? '完成' : '下一步' }}
                </button>
              </div>
            </div>
          </section>
        </Transition>
      </template>

      <template v-else-if="preferenceVisible">
        <div class="rm-tour-mask rm-tour-mask-full" @pointerdown.stop.prevent @click.stop.prevent></div>
        <Transition name="rm-tour-preference" appear>
          <section
            ref="preferenceRef"
            class="rm-tour-preference"
            role="dialog"
            aria-modal="true"
            aria-labelledby="rm-tour-preference-title"
            aria-describedby="rm-tour-preference-desc"
            tabindex="-1"
            @click.stop
          >
            <span class="rm-tour-preference-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="9"></circle>
                <path d="M9.4 9a2.7 2.7 0 1 1 4.7 1.8c-1.1.8-2.1 1.3-2.1 2.7"></path>
                <path d="M12 17h.01"></path>
              </svg>
            </span>
            <span class="rm-tour-eyebrow">导览偏好</span>
            <h2 id="rm-tour-preference-title">下次进入时还需要引导吗？</h2>
            <p id="rm-tour-preference-desc">无论选择哪项，都可以从顶栏帮助菜单重新查看。</p>
            <div class="rm-tour-preference-actions">
              <button type="button" class="rm-tour-secondary" @click="choosePreference('show')">下次仍然显示</button>
              <button type="button" class="rm-tour-primary" @click="choosePreference('never')">永不再显示</button>
            </div>
          </section>
        </Transition>
      </template>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";

const props = defineProps({
  active: { type: Boolean, default: false },
  preferenceVisible: { type: Boolean, default: false },
  steps: { type: Array, default: () => [] },
});

const emit = defineEmits(["step-change", "exit", "finish", "preference"]);

const currentIndex = ref(0);
const tooltipRef = ref(null);
const preferenceRef = ref(null);
const targetRect = ref(emptyRect());
const tooltipStyle = ref({ left: "16px", top: "16px" });
const placement = ref("bottom");
let targetElement = null;
let targetObserver = null;
let updateFrame = 0;
let settleTimer = 0;
let previousFocus = null;

const currentStep = computed(() => props.steps[currentIndex.value] || null);
const isLastStep = computed(() => currentIndex.value >= props.steps.length - 1);
const progressPercent = computed(() => props.steps.length ? ((currentIndex.value + 1) / props.steps.length) * 100 : 0);

const spotlightStyle = computed(() => rectStyle(targetRect.value));
const maskStyles = computed(() => {
  const rect = targetRect.value;
  const viewportWidth = window.innerWidth;
  const viewportHeight = window.innerHeight;
  return {
    top: pxRect(0, 0, viewportWidth, rect.top),
    left: pxRect(0, rect.top, rect.left, rect.height),
    right: pxRect(rect.right, rect.top, Math.max(0, viewportWidth - rect.right), rect.height),
    bottom: pxRect(0, rect.bottom, viewportWidth, Math.max(0, viewportHeight - rect.bottom)),
  };
});

function emptyRect() {
  return { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 };
}

function pxRect(left, top, width, height) {
  return {
    left: `${Math.max(0, left)}px`,
    top: `${Math.max(0, top)}px`,
    width: `${Math.max(0, width)}px`,
    height: `${Math.max(0, height)}px`,
  };
}

function rectStyle(rect) {
  return {
    left: `${rect.left}px`,
    top: `${rect.top}px`,
    width: `${rect.width}px`,
    height: `${rect.height}px`,
  };
}

function elementHasArea(element) {
  if (!element) return false;
  const rect = element.getBoundingClientRect();
  return rect.width > 1 && rect.height > 1;
}

async function findTarget(step) {
  const selector = step?.target;
  const fallbackSelector = step?.fallbackTarget;
  for (let attempt = 0; attempt < 45; attempt += 1) {
    const primary = selector ? document.querySelector(selector) : null;
    if (elementHasArea(primary)) return primary;
    const fallback = fallbackSelector ? document.querySelector(fallbackSelector) : null;
    if (elementHasArea(fallback)) return fallback;
    await new Promise((resolve) => requestAnimationFrame(resolve));
  }
  return document.querySelector(selector) || document.querySelector(fallbackSelector) || null;
}

function targetIsFullyVisible(element) {
  const rect = element?.getBoundingClientRect?.();
  if (!rect) return true;
  return rect.top >= 8 && rect.left >= 8 && rect.bottom <= window.innerHeight - 8 && rect.right <= window.innerWidth - 8;
}

async function activateStep(index) {
  if (!props.active || !props.steps.length) return;
  window.clearTimeout(settleTimer);
  currentIndex.value = Math.min(Math.max(index, 0), props.steps.length - 1);
  const step = currentStep.value;
  emit("step-change", step, currentIndex.value);
  await nextTick();
  await new Promise((resolve) => requestAnimationFrame(resolve));

  targetObserver?.disconnect();
  targetElement = await findTarget(step);
  if (targetElement && !targetIsFullyVisible(targetElement)) {
    targetElement.scrollIntoView({
      behavior: window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches ? "auto" : "smooth",
      block: "center",
      inline: "center",
    });
    await new Promise((resolve) => window.setTimeout(resolve, 220));
  }

  if (targetElement && typeof ResizeObserver !== "undefined") {
    targetObserver = new ResizeObserver(schedulePositionUpdate);
    targetObserver.observe(targetElement);
  }
  await nextTick();
  updatePosition();
  tooltipRef.value?.focus?.({ preventScroll: true });
  settleTimer = window.setTimeout(() => {
    updatePosition();
    tooltipRef.value?.focus?.({ preventScroll: true });
  }, 180);
}

function paddedTargetRect() {
  const raw = targetElement?.getBoundingClientRect?.();
  if (!raw || raw.width <= 1 || raw.height <= 1) {
    const width = Math.min(260, window.innerWidth - 32);
    const height = 88;
    const left = (window.innerWidth - width) / 2;
    const top = Math.max(16, (window.innerHeight - height) / 2 - 80);
    return { left, top, right: left + width, bottom: top + height, width, height };
  }
  const padding = Number(currentStep.value?.padding ?? 10);
  const margin = 6;
  const left = Math.max(margin, raw.left - padding);
  const top = Math.max(margin, raw.top - padding);
  const right = Math.min(window.innerWidth - margin, raw.right + padding);
  const bottom = Math.min(window.innerHeight - margin, raw.bottom + padding);
  return { left, top, right, bottom, width: Math.max(0, right - left), height: Math.max(0, bottom - top) };
}

function updatePosition() {
  if (!props.active) return;
  const rect = paddedTargetRect();
  targetRect.value = rect;
  const tooltip = tooltipRef.value?.getBoundingClientRect?.();
  const tooltipWidth = tooltip?.width || Math.min(368, window.innerWidth - 32);
  const tooltipHeight = tooltip?.height || 244;
  const gap = 14;
  const edge = 16;
  const available = {
    top: rect.top - edge,
    bottom: window.innerHeight - rect.bottom - edge,
    left: rect.left - edge,
    right: window.innerWidth - rect.right - edge,
  };
  const fits = {
    top: available.top >= tooltipHeight + gap,
    bottom: available.bottom >= tooltipHeight + gap,
    left: available.left >= tooltipWidth + gap,
    right: available.right >= tooltipWidth + gap,
  };
  const preferred = currentStep.value?.placement;
  const order = [preferred, "bottom", "right", "left", "top"].filter((item, index, all) => item && all.indexOf(item) === index);
  let nextPlacement = order.find((item) => fits[item]);
  if (!nextPlacement) {
    nextPlacement = Object.entries(available).sort((a, b) => b[1] - a[1])[0]?.[0] || "bottom";
  }

  let left;
  let top;
  if (nextPlacement === "top" || nextPlacement === "bottom") {
    left = rect.left + (rect.width - tooltipWidth) / 2;
    top = nextPlacement === "top" ? rect.top - tooltipHeight - gap : rect.bottom + gap;
  } else {
    left = nextPlacement === "left" ? rect.left - tooltipWidth - gap : rect.right + gap;
    top = rect.top + (rect.height - tooltipHeight) / 2;
  }
  left = Math.min(Math.max(edge, left), Math.max(edge, window.innerWidth - tooltipWidth - edge));
  top = Math.min(Math.max(edge, top), Math.max(edge, window.innerHeight - tooltipHeight - edge));
  placement.value = nextPlacement;
  tooltipStyle.value = { left: `${left}px`, top: `${top}px` };
}

function schedulePositionUpdate() {
  cancelAnimationFrame(updateFrame);
  updateFrame = requestAnimationFrame(updatePosition);
}

function previousStep() {
  if (currentIndex.value > 0) activateStep(currentIndex.value - 1);
}

function nextStep() {
  if (isLastStep.value) {
    emit("finish");
    return;
  }
  activateStep(currentIndex.value + 1);
}

function requestExit() {
  emit("exit");
}

function choosePreference(value) {
  emit("preference", value);
}

function focusableElements() {
  const root = props.active ? tooltipRef.value : preferenceRef.value;
  if (!root) return [];
  return [...root.querySelectorAll('button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex="-1"])')]
    .filter((element) => !element.hasAttribute("hidden"));
}

function handleKeydown(event) {
  if (!props.active && !props.preferenceVisible) return;
  if (event.key === "Escape" && props.active) {
    event.preventDefault();
    event.stopPropagation();
    requestExit();
    return;
  }
  if (event.key !== "Tab") return;
  const focusable = focusableElements();
  if (!focusable.length) {
    event.preventDefault();
    return;
  }
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (event.shiftKey && (document.activeElement === first || !focusable.includes(document.activeElement))) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

watch(
  () => props.active,
  (isActive) => {
    if (isActive) {
      previousFocus = document.activeElement;
      document.addEventListener("keydown", handleKeydown, true);
      window.addEventListener("resize", schedulePositionUpdate, { passive: true });
      window.addEventListener("scroll", schedulePositionUpdate, true);
      activateStep(0);
    } else {
      window.clearTimeout(settleTimer);
      targetObserver?.disconnect();
      targetElement = null;
      if (!props.preferenceVisible) {
        document.removeEventListener("keydown", handleKeydown, true);
        window.removeEventListener("resize", schedulePositionUpdate);
        window.removeEventListener("scroll", schedulePositionUpdate, true);
      }
    }
  },
);

watch(
  () => props.preferenceVisible,
  async (isVisible) => {
    if (isVisible) {
      document.addEventListener("keydown", handleKeydown, true);
      await nextTick();
      preferenceRef.value?.focus?.({ preventScroll: true });
    } else if (!props.active) {
      document.removeEventListener("keydown", handleKeydown, true);
      window.removeEventListener("resize", schedulePositionUpdate);
      window.removeEventListener("scroll", schedulePositionUpdate, true);
      previousFocus?.focus?.({ preventScroll: true });
      previousFocus = null;
    }
  },
);

onBeforeUnmount(() => {
  window.clearTimeout(settleTimer);
  cancelAnimationFrame(updateFrame);
  targetObserver?.disconnect();
  document.removeEventListener("keydown", handleKeydown, true);
  window.removeEventListener("resize", schedulePositionUpdate);
  window.removeEventListener("scroll", schedulePositionUpdate, true);
});
</script>

<style scoped>
.rm-tour-layer {
  position: fixed;
  inset: 0;
  z-index: 10000;
  pointer-events: none;
  font-family: var(--app-font-body, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif);
}

.rm-tour-mask {
  position: fixed;
  z-index: 0;
  pointer-events: auto;
  background: rgba(8, 22, 39, 0.62);
  transition: opacity 150ms ease-out;
}

.rm-tour-mask-full {
  inset: 0;
  width: auto;
  height: auto;
}

.rm-tour-spotlight {
  position: fixed;
  z-index: 1;
  box-sizing: border-box;
  border: 2px solid rgba(126, 190, 255, 0.98);
  border-radius: 14px;
  pointer-events: none;
  box-shadow: 0 0 0 1px rgba(241, 247, 255, 0.92), 0 0 0 5px rgba(57, 140, 231, 0.18), 0 14px 34px rgba(5, 21, 40, 0.2);
}

.rm-tour-bubble,
.rm-tour-preference {
  position: fixed;
  z-index: 2;
  box-sizing: border-box;
  pointer-events: auto;
  color: var(--app-ink, oklch(30% 0.02 250));
  background: var(--app-card-bg, oklch(99% 0.006 245));
  border: 1px solid var(--app-border-strong, oklch(84% 0.035 250));
  box-shadow: 0 24px 64px -26px rgba(4, 25, 52, 0.52), 0 8px 24px -16px rgba(4, 25, 52, 0.24);
  outline: none;
}

.rm-tour-bubble {
  width: min(368px, calc(100vw - 32px));
  padding: 20px;
  border-radius: 16px;
}

.rm-tour-eyebrow {
  display: block;
  margin-bottom: 8px;
  color: var(--app-blue, oklch(50% 0.17 252));
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.08em;
}

.rm-tour-bubble h2,
.rm-tour-preference h2 {
  margin: 0;
  color: var(--app-ink, oklch(27% 0.025 250));
  font-size: 20px;
  line-height: 1.25;
  font-weight: 720;
  letter-spacing: -0.02em;
}

.rm-tour-bubble p,
.rm-tour-preference p {
  margin: 10px 0 0;
  color: var(--app-muted, oklch(49% 0.025 250));
  font-size: 14px;
  line-height: 1.58;
}

.rm-tour-progress {
  height: 3px;
  margin-top: 18px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--app-blue-soft, oklch(92% 0.018 250));
}

.rm-tour-progress span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--app-blue, oklch(58% 0.19 252));
  transition: width 180ms cubic-bezier(0.32, 0.72, 0, 1);
}

.rm-tour-progress-text {
  margin-top: 7px;
  color: var(--app-muted, oklch(56% 0.018 250));
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.rm-tour-actions,
.rm-tour-preference-actions,
.rm-tour-nav-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rm-tour-actions {
  justify-content: space-between;
  margin-top: 18px;
}

.rm-tour-nav-actions {
  justify-content: flex-end;
}

.rm-tour-bubble button,
.rm-tour-preference button {
  min-height: 36px;
  padding: 0 14px;
  border-radius: 9px;
  font: inherit;
  font-size: 13px;
  font-weight: 680;
  cursor: pointer;
  transition: color 150ms ease-out, background-color 150ms ease-out, border-color 150ms ease-out, transform 150ms ease-out, box-shadow 150ms ease-out;
}

.rm-tour-bubble button:focus-visible,
.rm-tour-preference button:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px rgba(0, 113, 227, 0.22);
}

.rm-tour-bubble button:active,
.rm-tour-preference button:active {
  transform: translateY(1px);
}

.rm-tour-bubble button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
  transform: none;
}

.rm-tour-skip {
  border: 0;
  color: var(--app-muted, oklch(49% 0.025 250));
  background: transparent;
}

.rm-tour-skip:hover {
  color: oklch(37% 0.04 250);
  background: oklch(94% 0.018 250);
}

.rm-tour-secondary {
  border: 1px solid oklch(84% 0.03 250);
  color: var(--app-ink, oklch(39% 0.03 250));
  background: var(--app-card-bg, oklch(98% 0.006 250));
}

.rm-tour-secondary:hover:not(:disabled) {
  border-color: oklch(72% 0.075 250);
  background: oklch(95% 0.02 250);
}

.rm-tour-primary {
  border: 1px solid oklch(52% 0.19 252);
  color: oklch(98% 0.01 250);
  background: var(--app-blue, oklch(54% 0.19 252));
  box-shadow: 0 8px 18px -10px rgba(0, 90, 188, 0.7);
}

.rm-tour-primary:hover {
  border-color: oklch(47% 0.19 252);
  background: oklch(49% 0.19 252);
}

.rm-tour-preference {
  left: 50%;
  top: 50%;
  width: min(430px, calc(100vw - 32px));
  padding: 26px;
  border-radius: 18px;
  transform: translate(-50%, -50%);
}

.rm-tour-preference-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  margin-bottom: 16px;
  border-radius: 12px;
  color: var(--app-blue, oklch(50% 0.17 252));
  background: var(--app-blue-soft, oklch(94% 0.035 250));
}

.rm-tour-preference-icon svg {
  width: 22px;
  height: 22px;
}

.rm-tour-preference-actions {
  justify-content: flex-end;
  margin-top: 22px;
}

.rm-tour-bubble-enter-active,
.rm-tour-bubble-leave-active,
.rm-tour-preference-enter-active,
.rm-tour-preference-leave-active {
  transition: opacity 150ms ease-out, transform 150ms cubic-bezier(0.32, 0.72, 0, 1);
}

.rm-tour-bubble-enter-from,
.rm-tour-bubble-leave-to {
  opacity: 0;
  transform: translateY(5px) scale(0.985);
}

.rm-tour-preference-enter-from,
.rm-tour-preference-leave-to {
  opacity: 0;
  transform: translate(-50%, calc(-50% + 6px)) scale(0.985);
}

@media (max-width: 640px) {
  .rm-tour-bubble {
    padding: 17px;
    border-radius: 14px;
  }

  .rm-tour-bubble h2,
  .rm-tour-preference h2 {
    font-size: 18px;
  }

  .rm-tour-bubble p,
  .rm-tour-preference p {
    font-size: 14px;
  }

  .rm-tour-actions {
    align-items: flex-end;
  }

  .rm-tour-nav-actions {
    flex-wrap: wrap;
  }

  .rm-tour-bubble button,
  .rm-tour-preference button {
    min-height: 44px;
    padding: 0 12px;
  }

  .rm-tour-preference-actions {
    align-items: stretch;
    flex-direction: column;
  }
}

@media (prefers-reduced-motion: reduce) {
  .rm-tour-mask,
  .rm-tour-spotlight,
  .rm-tour-progress span,
  .rm-tour-bubble-enter-active,
  .rm-tour-bubble-leave-active,
  .rm-tour-preference-enter-active,
  .rm-tour-preference-leave-active {
    transition-duration: 1ms !important;
  }
}

/* ── 暗色模式（html.dark，跟随底图选择）：字面量 hover/按钮态的对应覆盖 ── */
html.dark .rm-tour-mask {
  background: rgba(4, 8, 14, 0.7);
}

html.dark .rm-tour-bubble,
html.dark .rm-tour-preference {
  box-shadow: 0 24px 64px -26px rgba(2, 6, 12, 0.8), 0 8px 24px -16px rgba(2, 6, 12, 0.5);
}

html.dark .rm-tour-skip:hover {
  color: oklch(88% 0.02 250);
  background: rgba(148, 180, 220, 0.12);
}

html.dark .rm-tour-secondary {
  border-color: rgba(148, 180, 220, 0.28);
}

html.dark .rm-tour-secondary:hover:not(:disabled) {
  border-color: rgba(148, 180, 220, 0.45);
  background: rgba(148, 180, 220, 0.12);
}

html.dark .rm-tour-primary {
  border-color: oklch(58% 0.17 253);
  box-shadow: 0 8px 18px -10px rgba(2, 6, 12, 0.8);
}

html.dark .rm-tour-primary:hover {
  border-color: oklch(66% 0.16 253);
  background: oklch(66% 0.16 253);
}
</style>
