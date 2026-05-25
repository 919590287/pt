import { Layer } from "../main/Layer";

export class MapLayer extends Layer {
  name = "MapLayer";

  constructor(opt = {}) {
    super(opt);
    this.tileClass = opt.tileClass;
    this.opacity = opt.opacity ?? 1;
  }

  onAdd(map) {
    super.onAdd(map);
    if (this.opacity !== 1) {
      map.whenReady(() => {
        if (map.map.getLayer("base-raster")) {
          map.map.setPaintProperty("base-raster", "raster-opacity", this.opacity);
        }
      });
    }
  }
}

export function MapStyleFactory(params = {}) {
  return params;
}

export const MAP_LAYER_STYLE = window.MAP_LAYER_STYLE || [{}];

export const DEFAULT_MAP_LAYER_STYLE = MAP_LAYER_STYLE[window.DEFAULT_MAP_LAYER_STYLE_INDEX || 0] || MAP_LAYER_STYLE[0];
