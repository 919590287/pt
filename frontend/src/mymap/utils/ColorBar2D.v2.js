import * as THREE from "three";

const canvas2D = window.__ColorBar2DCanvas || (window.__ColorBar2DCanvas = document.createElement("canvas"));
canvas2D.width = 1024;
canvas2D.height = 16;
canvas2D.style.cssText = `
  position: fixed;
  left: 0;
  top: -30px;
  width: 1024px;
  height: 16px;
  z-index: -9999;
`;
document.body.appendChild(canvas2D);
const context2D = canvas2D.getContext("2d");

export class ColorBar2D {
  list = [];

  constructor(list = []) {
    try {
      this._min = list[0].range[0];
      this._max = list[0].range[1];
    } catch (error) {
      this._min = 0;
      this._max = 1;
    }
    this.list = list.map((v) => new ColorBar2DItem(v)).sort((a, b) => a.min - b.min);
  }

  // get min() {
  //   let i = 0;
  //   let v = this.list[i];
  //   while (v && !v.use) {
  //     v = this.list[++i];
  //   }
  //   return !!v ? v.min : 0;
  // }

  // get max() {
  //   let i = this.list.length - 1;
  //   let v = this.list[i];
  //   while (v && !v.use) {
  //     v = this.list[--i];
  //   }
  //   return !!v ? v.max : 1;
  // }

  get min() {
    return this._min;
  }
  get max() {
    return this._max;
  }

  getColor(value) {
    try {
      // 创建线性渐变色
      for (let i = this.list.length - 1; i >= 0; i--) {
        const { min, max, color } = this.list[i];
        if (min <= value && value <= max) {
          return color;
        }
      }
      return "#00000000";
    } catch (error) {
      return "#00000000";
    }
  }

  getImage() {
    try {
      // 清空画布
      context2D.clearRect(0, 0, canvas2D.width, canvas2D.height);
      const MIN = this.min;
      const MAX = this.max;
      // console.log(this.list);
      for (const { min, max, color, use } of this.list) {
        if (use) {
          const x = ((min - MIN) / (MAX - MIN)) * canvas2D.width;
          const y = 0;
          const width = ((max - min) / (MAX - MIN)) * canvas2D.width;
          const height = canvas2D.height;
          // 绘制渐变色条
          context2D.fillStyle = color;
          context2D.fillRect(x, y, width, height);
        }
      }
      const url = canvas2D.toDataURL("image/png");
      // 再次清空画布
      // context2D.clearRect(0, 0, canvas2D.width, canvas2D.height);
      console.log("ColorBarUrl:", url);
      return url;
    } catch (error) {
      console.error(error);
      return null;
    }
  }
}

class ColorBar2DItem {
  constructor({ min, max, label, color, use }) {
    this.min = min;
    if (min !== 0 && !min) {
      this.min = Number.MIN_SAFE_INTEGER;
    }
    this.max = max;
    if (max !== 0 && !max) {
      this.max = Number.MAX_SAFE_INTEGER;
    }
    this.label = label;
    this.color = color;
    this.use = use;
  }
}
