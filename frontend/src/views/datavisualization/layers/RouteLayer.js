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
    return super.currentTileDetail();
  }
}
