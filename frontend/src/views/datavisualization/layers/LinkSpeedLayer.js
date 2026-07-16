// 车辆运行监测：路段公交车速图层管理器（deck LineLayer，随播放时钟按 15min 桶换色）。
// - 几何：有向链路一次性预计算（含行驶方向右偏 4.5m，双向路段左右分线不互相压盖，
//   低缩放两线视觉合一、高缩放自然分离——导航路况同款观感）；
// - 颜色：绝对速度分档（MAP_THEME.linkSpeed，深红=严重拥堵→绿=畅通），
//   当前桶无班次经过的链路以灰色同宽保留（公交线网轮廓完整，灰=无数据而非猜测）；
// - 桶切换只重建 colors 列并换层实例（deck 按 id diff attribute，~10 万段毫秒级）；
// - 透明度走 deck layer 级 opacity（不重建颜色列）；
// - pickable=false：播放页对指针拾取的性能敏感（悬浮 tooltip 留待后续需求）。

import { LineLayer } from "@deck.gl/layers";
import { removeSharedDeckLayer, setSharedDeckLayer } from "./deckOverlayRegistry.js";
import { MAP_THEME, hexToRgbArray } from "@/utils/mapTheme.js";
import { classifyByBreaks } from "@/utils/colorSchemes.js";
import { mercatorToLngLat } from "../utils/populationGrid.js";

export const LINK_SPEED_LAYER_KEY = "rm-link-speed";
export const LINK_SPEED_HIGHLIGHT_LAYER_KEY = "rm-link-speed-highlight";
// 共享 deck overlay 内的层序：压在专题底层（0）之上、车辆轨迹（1200）之下
const DECK_ORDER = 900;
// 高亮描边垫在车速线（900）之下，宽出的部分露出成描边，不遮路况颜色
const HIGHLIGHT_DECK_ORDER = 890;
// 行驶方向右偏（米，Web Mercator 平面）：中国右侧通行，双向链路各让半幅
const DIRECTION_OFFSET_M = 4.5;

export class LinkSpeedLayerManager {
  constructor() {
    this.mapWrapper = null;
    this.data = null; // parseLinkSpeedMatrix 结果（markRaw 后传入）
    this.source = null; // Float64Array count×2（偏移后起点经纬度）
    this.target = null; // Float64Array count×2（偏移后终点经纬度）
    this.colors = null; // Uint8Array count×4（当前桶分档色，无数据 alpha=0）
    this.bucket = -1;
    this.opacity = 1;
    this.visible = false;
    // 分档色预展开为 RGB 数组，桶切换热路径零解析
    this.bandColors = MAP_THEME.linkSpeed.colors.map((hex) => hexToRgbArray(hex));
    this.alpha = MAP_THEME.linkSpeed.alpha;
    this.breaks = MAP_THEME.linkSpeed.breaks;
    this.noDataColor = hexToRgbArray(MAP_THEME.linkSpeed.noData || "#9aa3ad");
    this.noDataAlpha = MAP_THEME.linkSpeed.noDataAlpha ?? 130;
  }

  attach(mapWrapper) {
    this.mapWrapper = mapWrapper;
  }

  /** 灌入解析后的矩阵数据并预计算偏移几何；再次调用（换模型）整体替换。 */
  setData(data) {
    this.data = data || null;
    this.bucket = -1;
    this.colors = null;
    if (!data || !data.count) {
      this.source = null;
      this.target = null;
      this.commit();
      return;
    }
    const count = data.count;
    const source = new Float64Array(count * 2);
    const target = new Float64Array(count * 2);
    for (let k = 0; k < count; k++) {
      const x1 = data.x1[k];
      const y1 = data.y1[k];
      const x2 = data.x2[k];
      const y2 = data.y2[k];
      const dx = x2 - x1;
      const dy = y2 - y1;
      const length = Math.hypot(dx, dy);
      // 行驶方向右侧法向（x 东 y 北）：(dy, -dx)/len；零长链路不偏移
      const ox = length > 0 ? (dy / length) * DIRECTION_OFFSET_M : 0;
      const oy = length > 0 ? (-dx / length) * DIRECTION_OFFSET_M : 0;
      const [srcLng, srcLat] = mercatorToLngLat(x1 + ox, y1 + oy);
      const [dstLng, dstLat] = mercatorToLngLat(x2 + ox, y2 + oy);
      source[k * 2] = srcLng;
      source[k * 2 + 1] = srcLat;
      target[k * 2] = dstLng;
      target[k * 2 + 1] = dstLat;
    }
    this.source = source;
    this.target = target;
  }

  /** 播放时钟跨桶时调用：重建当前桶颜色列并提交（同桶幂等）。 */
  setBucket(bucket) {
    if (!this.data || !this.data.count) return;
    const clamped = Math.max(0, Math.min(this.data.buckets - 1, bucket | 0));
    if (clamped === this.bucket && this.colors) return;
    this.bucket = clamped;
    const { count, buckets, speeds } = this.data;
    const colors = new Uint8Array(count * 4);
    for (let k = 0; k < count; k++) {
      const speed = speeds[k * buckets + clamped];
      // 无班次经过：灰色同宽保留，公交线网轮廓不随时段碎裂
      const band = speed === 0 ? this.noDataColor : this.bandColors[classifyByBreaks(speed, this.breaks)];
      colors[k * 4] = band[0];
      colors[k * 4 + 1] = band[1];
      colors[k * 4 + 2] = band[2];
      colors[k * 4 + 3] = speed === 0 ? this.noDataAlpha : this.alpha;
    }
    this.colors = colors;
    this.commit();
  }

  /** 图层整体透明度（0-1），不重建颜色列。 */
  setOpacity(opacity) {
    const next = Math.max(0, Math.min(1, Number(opacity) || 0));
    if (next === this.opacity) return;
    this.opacity = next;
    this.commit();
  }

  setVisible(visible) {
    const next = Boolean(visible);
    if (next === this.visible) return;
    this.visible = next;
    this.commit();
  }

  /** 依据当前状态提交/移除共享 deck 层。地图未就绪时静默，由调用方在激活时补 commit。 */
  commit() {
    const wrapper = this.mapWrapper;
    if (!wrapper) return;
    if (!this.visible || !this.data || !this.data.count || !this.colors) {
      removeSharedDeckLayer(wrapper, LINK_SPEED_LAYER_KEY);
      return;
    }
    const layer = new LineLayer({
      id: LINK_SPEED_LAYER_KEY,
      data: {
        length: this.data.count,
        attributes: {
          getSourcePosition: { value: this.source, size: 2 },
          getTargetPosition: { value: this.target, size: 2 },
          getColor: { value: this.colors, size: 4 },
        },
      },
      opacity: this.opacity,
      getWidth: MAP_THEME.linkSpeed.widthPx,
      widthUnits: "pixels",
      widthMinPixels: 1,
      pickable: false,
    });
    setSharedDeckLayer(wrapper, LINK_SPEED_LAYER_KEY, layer, DECK_ORDER);
  }

  dispose() {
    if (this.mapWrapper) {
      removeSharedDeckLayer(this.mapWrapper, LINK_SPEED_LAYER_KEY);
    }
    this.mapWrapper = null;
    this.data = null;
    this.source = null;
    this.target = null;
    this.colors = null;
  }
}

/**
 * 拥堵 TOP 榜点击定位的路段高亮（deck LineLayer）。
 * 垫在车速图层之下（order 890 < 900）且更宽，形成描边：路况颜色仍可见；
 * 车速图层未开启的瞬间它独立可见，用户不丢定位反馈。
 * 几何右偏量与 LinkSpeedLayerManager.setData 保持一致，描边才能正好包住车速线。
 */
export class LinkSpeedHighlightManager {
  constructor() {
    this.mapWrapper = null;
    this.data = null; // parseLinkSpeedMatrix 结果（与车速图层共享同一份 markRaw 对象）
    this.links = [];
    this.color = [...hexToRgbArray(MAP_THEME.linkSpeed.highlight || "#2166f3"),
      MAP_THEME.linkSpeed.highlightAlpha ?? 235];
    this.widthPx = MAP_THEME.linkSpeed.highlightWidthPx ?? 9;
  }

  attach(mapWrapper) {
    this.mapWrapper = mapWrapper;
  }

  setData(data) {
    this.data = data || null;
    if (this.links.length) {
      this.highlight([]);
    }
  }

  /** 高亮一组链路（矩阵行下标数组）；空数组即清除。 */
  highlight(links) {
    this.links = Array.isArray(links) ? links.filter((k) => this.isValidLink(k)) : [];
    this.commit();
  }

  clear() {
    if (this.links.length) {
      this.highlight([]);
    }
  }

  isValidLink(k) {
    return Number.isInteger(k) && this.data && k >= 0 && k < this.data.count;
  }

  commit() {
    const wrapper = this.mapWrapper;
    if (!wrapper) return;
    if (!this.links.length || !this.data) {
      removeSharedDeckLayer(wrapper, LINK_SPEED_HIGHLIGHT_LAYER_KEY);
      return;
    }
    const { x1, y1, x2, y2 } = this.data;
    const count = this.links.length;
    const source = new Float64Array(count * 2);
    const target = new Float64Array(count * 2);
    for (let i = 0; i < count; i++) {
      const k = this.links[i];
      const dx = x2[k] - x1[k];
      const dy = y2[k] - y1[k];
      const length = Math.hypot(dx, dy);
      const ox = length > 0 ? (dy / length) * DIRECTION_OFFSET_M : 0;
      const oy = length > 0 ? (-dx / length) * DIRECTION_OFFSET_M : 0;
      const [srcLng, srcLat] = mercatorToLngLat(x1[k] + ox, y1[k] + oy);
      const [dstLng, dstLat] = mercatorToLngLat(x2[k] + ox, y2[k] + oy);
      source[i * 2] = srcLng;
      source[i * 2 + 1] = srcLat;
      target[i * 2] = dstLng;
      target[i * 2 + 1] = dstLat;
    }
    const layer = new LineLayer({
      id: LINK_SPEED_HIGHLIGHT_LAYER_KEY,
      data: {
        length: count,
        attributes: {
          getSourcePosition: { value: source, size: 2 },
          getTargetPosition: { value: target, size: 2 },
        },
      },
      getColor: this.color,
      getWidth: this.widthPx,
      widthUnits: "pixels",
      widthMinPixels: 3,
      pickable: false,
    });
    setSharedDeckLayer(wrapper, LINK_SPEED_HIGHLIGHT_LAYER_KEY, layer, HIGHLIGHT_DECK_ORDER);
  }

  dispose() {
    if (this.mapWrapper) {
      removeSharedDeckLayer(this.mapWrapper, LINK_SPEED_HIGHLIGHT_LAYER_KEY);
    }
    this.mapWrapper = null;
    this.data = null;
    this.links = [];
  }
}
