let ColorBar2DInstance = null;

/*
颜色数据支持的两种格式
["#313695", "#74add1", "#e0f3f8", "#ffffbf", "#fdae61", "#f46d43", "#a50026"]
{
  "#313695": [0, 0.4],
  "#74add1": [0.4, 0.6],
  "#e0f3f8": [0.6, 0.75],
  "#ffffbf": [0.75, 0.85],
  "#fdae61": [0.85, 0.95],
  "#f46d43": [0.95, 1.0],
}
 */

const defaultColors = {
  "#313695": [0, 0.4],
  "#74add1": [0.4, 0.6],
  "#e0f3f8": [0.6, 0.75],
  "#ffffbf": [0.75, 0.85],
  "#fdae61": [0.85, 0.95],
  "#f46d43": [0.95, 1.0],
};

function checkKey(key) {
  const _key = Number(Number(key).toFixed(4));
  if (_key < 0) return 0;
  if (_key > 1) return 1;
  return _key;
}

export class ColorBar2D {
  static width = 1024;
  static height = 16;
  static get defaultColors() {
    return defaultColors;
  }

  static get instance() {
    if (!ColorBar2DInstance) {
      ColorBar2DInstance = new ColorBar2D();
    }
    return ColorBar2DInstance;
  }

  get canvas2D() {
    if (!this._canvas2D) {
      this._canvas2D = document.createElement("canvas");
      this._canvas2D.width = ColorBar2D.width;
      this._canvas2D.height = ColorBar2D.height;
      this._canvas2D.style.cssText = `
        position: absolute;
        left: 0;
        top: 0;
        width: ${ColorBar2D.width}px;
        height: ${ColorBar2D.height}px;
        z-index: 9999;
      `;
    }
    return this._canvas2D;
  }

  get context2D() {
    if (!this._context2D) {
      this._context2D = this.canvas2D.getContext("2d");
    }
    return this._context2D;
  }

  get testImg() {
    if (!this._testImg) {
      this._testImg = document.createElement("img");
      this._testImg.style = "position: fixed;left: 0;top: 0;width: 1024px;height: 16px;z-index: 9999;";
      document.body.appendChild(this._testImg);
    }
    return this._testImg;
  }

  static getDrowColors(colors) {
    const list = [];
    if (colors instanceof Array) {
      const d = Number(Number(1 / colors.length).toFixed(4));
      for (let i = 0; i < colors.length; i++) {
        list.push({ key: checkKey(i * d), color: colors[i] });
        list.push({ key: checkKey((i + 1) * d), color: colors[i] });
      }
    } else if (colors instanceof Object) {
      //颜色条的颜色分布
      const values = Object.values(colors).flat(3).map((v) => Number(v)).sort();
      const maxValue = values[values.length - 1];
      const minValue = values[0];
      for (const [value, keys] of Object.entries(colors)) {
        for (const key of keys) {
          list.push({ key: checkKey((Number(key) - minValue) / maxValue - minValue), color: value });
        }
      }
    } else {
      throw new Error("colors参数类型错误");
    }
    return list.sort((a, b) => a.key - b.key);
  }

  static getColor(value, min, max, colors) {
    try {
      const drowColors = ColorBar2D.getDrowColors(colors);
      const _value = (Number(value) - min) / (max - min);

      let prov = null
      for (const item of drowColors) {
        if (prov === null) {
          if (_value == item.key) {
            return item.color
          } else {
            prov = item.key
          }
        } else {
          if (prov < _value && _value <= item.key) {
            return item.color
          }
        }
      }
      return "red"
    } catch (error) {
      console.log(error);
      return "red"
    }
  }

  // 渐变颜色条图
  drow(colors = defaultColors) {
    try {

      // 创建线性渐变色
      const linearGradient = this.context2D.createLinearGradient(0, 0, ColorBar2D.width, 0);
      const drowColors = ColorBar2D.getDrowColors(colors);
      for (const { key, color } of drowColors) {
        linearGradient.addColorStop(key, color);
      }
      // 绘制渐变色条
      this.context2D.fillStyle = linearGradient;
      this.context2D.fillRect(0, 0, ColorBar2D.width, ColorBar2D.height);
      const url = this.canvas2D.toDataURL("image/png");
      this.context2D.clearRect(0, 0, ColorBar2D.width, ColorBar2D.height);
      // this.testImg.src = url;
      console.log("ColorBarUrl:", url);

      return url;
    } catch (error) {
      console.error(error);

      return null;
    }
  }
}
