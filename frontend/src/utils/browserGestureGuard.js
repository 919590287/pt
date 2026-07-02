/**
 * 全局屏蔽浏览器手势，避免与地图交互冲突：
 * ① 右键拖动地图时浏览器的鼠标手势/系统拖拽/右键菜单（与数据管理页同一套判定）；
 * ② 触控板双指横扫触发的前进/后退与页面回弹（overscroll-behavior）；
 * ③ Safari 触控板捏合缩放页面（gesturestart/gesturechange/gestureend）。
 */

const RIGHT_MOUSE_BUTTON = 2;
const RIGHT_MOUSE_BUTTON_MASK = 2;
const EVENT_OPTIONS = { capture: true, passive: false };

let bound = false;
let suppressingRightMouse = false;

function preventDefaultIfPossible(event) {
  if (event?.cancelable) {
    event.preventDefault();
  }
}

function isRightMouseGestureEvent(event) {
  return event?.button === RIGHT_MOUSE_BUTTON
    || (event?.buttons & RIGHT_MOUSE_BUTTON_MASK) === RIGHT_MOUSE_BUTTON_MASK;
}

function handleMouseEvent(event) {
  if (!isRightMouseGestureEvent(event)) return;
  if (event.type === "mousedown") {
    suppressingRightMouse = true;
  }
  preventDefaultIfPossible(event);
}

function handleMouseUp(event) {
  if (!suppressingRightMouse && event?.button !== RIGHT_MOUSE_BUTTON) return;
  preventDefaultIfPossible(event);
  suppressingRightMouse = false;
}

function handleContextMenu(event) {
  if (!suppressingRightMouse && !isRightMouseGestureEvent(event)) return;
  preventDefaultIfPossible(event);
  suppressingRightMouse = false;
}

function handleDragStart(event) {
  if (!suppressingRightMouse) return;
  preventDefaultIfPossible(event);
}

function handleGesture(event) {
  preventDefaultIfPossible(event);
}

function resetState() {
  suppressingRightMouse = false;
}

export function bindBrowserGestureGuard() {
  if (bound || typeof window === "undefined") return;
  bound = true;
  document.documentElement.style.overscrollBehaviorX = "none";
  document.documentElement.style.overscrollBehaviorY = "none";
  if (document.body) {
    document.body.style.overscrollBehaviorX = "none";
    document.body.style.overscrollBehaviorY = "none";
  }
  window.addEventListener("mousedown", handleMouseEvent, EVENT_OPTIONS);
  window.addEventListener("mousemove", handleMouseEvent, EVENT_OPTIONS);
  window.addEventListener("mouseup", handleMouseUp, EVENT_OPTIONS);
  window.addEventListener("contextmenu", handleContextMenu, EVENT_OPTIONS);
  window.addEventListener("dragstart", handleDragStart, EVENT_OPTIONS);
  window.addEventListener("gesturestart", handleGesture, EVENT_OPTIONS);
  window.addEventListener("gesturechange", handleGesture, EVENT_OPTIONS);
  window.addEventListener("gestureend", handleGesture, EVENT_OPTIONS);
  window.addEventListener("blur", resetState);
}

export function unbindBrowserGestureGuard() {
  if (!bound || typeof window === "undefined") return;
  bound = false;
  document.documentElement.style.overscrollBehaviorX = "";
  document.documentElement.style.overscrollBehaviorY = "";
  if (document.body) {
    document.body.style.overscrollBehaviorX = "";
    document.body.style.overscrollBehaviorY = "";
  }
  window.removeEventListener("mousedown", handleMouseEvent, EVENT_OPTIONS);
  window.removeEventListener("mousemove", handleMouseEvent, EVENT_OPTIONS);
  window.removeEventListener("mouseup", handleMouseUp, EVENT_OPTIONS);
  window.removeEventListener("contextmenu", handleContextMenu, EVENT_OPTIONS);
  window.removeEventListener("dragstart", handleDragStart, EVENT_OPTIONS);
  window.removeEventListener("gesturestart", handleGesture, EVENT_OPTIONS);
  window.removeEventListener("gesturechange", handleGesture, EVENT_OPTIONS);
  window.removeEventListener("gestureend", handleGesture, EVENT_OPTIONS);
  window.removeEventListener("blur", resetState);
  resetState();
}
