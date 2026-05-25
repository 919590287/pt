import { RouteLayer } from "./RouteLayer.js";

export class HighlightSegmentLayer extends RouteLayer {
  name = "HighlightSegmentLayer";

  constructor(opt = {}) {
    super({
      zIndex: 1000,
      color: 0x10b981,
      opacity: 1,
      lineWidth: opt.lineWidth || 32,
      ...opt,
    });
  }

  setData(link) {
    super.setData(link ? [link] : []);
  }
}
