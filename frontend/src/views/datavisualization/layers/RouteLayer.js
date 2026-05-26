import { NetworkLayer } from "./NetworkLayer.js";
import { getRouteFullBinary } from "@/api/route.js";
import { colorToCss } from "./maplibreLayerUtils.js";

export class RouteLayer extends NetworkLayer {
  name = "RouteLayer";

  constructor(opt = {}) {
    super({
      color: opt.color ?? 0x1f78b4,
      opacity: opt.opacity ?? 1,
      fullRequest: opt.fullRequest || getRouteFullBinary,
      ...opt,
    });
    this.color = colorToCss(opt.color ?? 0x1f78b4);
    this.opacity = opt.opacity ?? 1;
  }

  currentTileDetail() {
    const mapZoom = Number(this.map?.zoom);
    if (!Number.isFinite(mapZoom)) {
      return { level: "all", z: 0, full: true };
    }
    if (mapZoom < this.fullModeMaxZoom) return { level: "all", z: 0, full: true };
    if (mapZoom >= 13.0) return { level: "full", z: 12 };
    if (mapZoom >= 11.7) return { level: "corridor", z: 12 };
    if (mapZoom >= 10.2) return { level: "district", z: 11 };
    if (mapZoom >= 8.8) return { level: "city", z: 10 };
    return { level: "overview", z: 8 };
  }
}
