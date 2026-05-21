import * as THREE from "three";

export default class extends THREE.Sprite {
  constructor(text = "", fontSize = 50, color = "rgba(255, 255, 255, 1)") {
    const canvas = document.createElement("canvas");
    const texture = new THREE.CanvasTexture(canvas);
    const material = new THREE.SpriteMaterial({ sizeAttenuation: false, transparent: true, map: texture });
    super(material);

    this._text = `${text}`;
    this._lineHeight = 1;
    this._color = color;
    this._backgroundColor = false; // no background color

    this._padding = 0;
    this._borderWidth = 0;
    this._borderRadius = 0;
    this._borderColor = "white";

    this._strokeWidth = 1;
    this._strokeColor = "rgba(0, 0, 0, 1)";

    this._fontFace = "system-ui";
    this._fontSize = fontSize; // defines text resolution
    this._fontWeight = "normal";
    this._canvas = canvas;
    this._texture = texture;
    this._genCanvas();
  }
  get canvas() {
    return this._canvas;
  }
  get texture() {
    return this._texture;
  }

  get text() {
    return this._text;
  }
  set text(text) {
    this._text = text;
    this._genCanvas();
  }
  get lineHeight() {
    return this._lineHeight;
  }
  set lineHeight(lineHeight) {
    this._lineHeight = lineHeight;
    this._genCanvas();
  }
  get color() {
    return this._color;
  }
  set color(color) {
    this._color = color;
    this._genCanvas();
  }
  get backgroundColor() {
    return this._backgroundColor;
  }
  set backgroundColor(color) {
    this._backgroundColor = color;
    this._genCanvas();
  }
  get padding() {
    return this._padding;
  }
  set padding(padding) {
    this._padding = padding;
    this._genCanvas();
  }
  get borderWidth() {
    return this._borderWidth;
  }
  set borderWidth(borderWidth) {
    this._borderWidth = borderWidth;
    this._genCanvas();
  }
  get borderRadius() {
    return this._borderRadius;
  }
  set borderRadius(borderRadius) {
    this._borderRadius = borderRadius;
    this._genCanvas();
  }
  get borderColor() {
    return this._borderColor;
  }
  set borderColor(borderColor) {
    this._borderColor = borderColor;
    this._genCanvas();
  }
  get fontFace() {
    return this._fontFace;
  }
  set fontFace(fontFace) {
    this._fontFace = fontFace;
    this._genCanvas();
  }
  get fontSize() {
    return this._fontSize;
  }
  set fontSize(fontSize) {
    this._fontSize = fontSize;
    this._genCanvas();
  }
  get fontWeight() {
    return this._fontWeight;
  }
  set fontWeight(fontWeight) {
    this._fontWeight = fontWeight;
    this._genCanvas();
  }
  get strokeWidth() {
    return this._strokeWidth;
  }
  set strokeWidth(strokeWidth) {
    this._strokeWidth = strokeWidth;
    this._genCanvas();
  }
  get strokeColor() {
    return this._strokeColor;
  }
  set strokeColor(strokeColor) {
    this._strokeColor = strokeColor;
    this._genCanvas();
  }

  _genCanvas() {
    const canvas = this.canvas;
    const ctx = canvas.getContext("2d");

    const relFactor = 4;

    const border = Array.isArray(this.borderWidth) ? this.borderWidth : [this.borderWidth, this.borderWidth]; // x,y border
    const relBorder = border.map((b) => b * this.fontSize * relFactor);

    const borderRadius = Array.isArray(this.borderRadius) ? this.borderRadius : [this.borderRadius, this.borderRadius, this.borderRadius, this.borderRadius]; // tl tr br bl corners
    const relBorderRadius = borderRadius.map((b) => b * this.fontSize * relFactor); // border radius in canvas units

    const padding = Array.isArray(this.padding) ? this.padding : [this.padding, this.padding]; // x,y padding
    const relPadding = padding.map((p) => p * this.fontSize * relFactor); // padding in canvas units

    const relFontSize = this.fontSize * relFactor;
    const lines = this.text.split("\n");
    const font = `${this.fontWeight} ${relFontSize}px ${this.fontFace}`;

    const drawTextStroke = this.strokeWidth > 0;
    if (drawTextStroke) {
      ctx.lineWidth = (this.strokeWidth * relFontSize) / 10;
    }

    ctx.font = font; // measure canvas with appropriate font
    const textWidthList = lines.map((line) => getTextWidth(ctx, line));
    const textHeightList = lines.map((line) => getTextHeight(ctx, line));
    const textWidth = Math.max(...textWidthList);
    const textHeight = Math.max(...textHeightList);
    const innerWidth = textWidth;
    const innerHeight = textHeight * lines.length * this.lineHeight;
    canvas.width = innerWidth + relBorder[0] * 2 + relPadding[0] * 2;
    canvas.height = innerHeight + relBorder[1] * 2 + relPadding[1] * 2;

    // paint border
    if (this.borderWidth) {
      ctx.strokeStyle = this.borderColor;

      if (relBorder[0]) {
        // left + right borders
        const hb = relBorder[0] / 2;
        ctx.lineWidth = relBorder[0];
        ctx.beginPath();
        ctx.moveTo(hb, relBorderRadius[0]);
        ctx.lineTo(hb, canvas.height - relBorderRadius[3]);
        ctx.moveTo(canvas.width - hb, relBorderRadius[1]);
        ctx.lineTo(canvas.width - hb, canvas.height - relBorderRadius[2]);
        ctx.stroke();
      }

      if (relBorder[1]) {
        // top + bottom borders
        const hb = relBorder[1] / 2;
        ctx.lineWidth = relBorder[1];
        ctx.beginPath();
        ctx.moveTo(Math.max(relBorder[0], relBorderRadius[0]), hb);
        ctx.lineTo(canvas.width - Math.max(relBorder[0], relBorderRadius[1]), hb);
        ctx.moveTo(Math.max(relBorder[0], relBorderRadius[3]), canvas.height - hb);
        ctx.lineTo(canvas.width - Math.max(relBorder[0], relBorderRadius[2]), canvas.height - hb);
        ctx.stroke();
      }

      if (this.relBorderRadius) {
        // strike rounded corners
        const cornerWidth = Math.max(...relBorder);
        const hb = cornerWidth / 2;
        ctx.lineWidth = cornerWidth;
        ctx.beginPath();
        [
          !!relBorderRadius[0] && [relBorderRadius[0], hb, hb, relBorderRadius[0]],
          !!relBorderRadius[1] && [canvas.width - relBorderRadius[1], canvas.width - hb, hb, relBorderRadius[1]],
          !!relBorderRadius[2] && [canvas.width - relBorderRadius[2], canvas.width - hb, canvas.height - hb, canvas.height - relBorderRadius[2]],
          !!relBorderRadius[3] && [relBorderRadius[3], hb, canvas.height - hb, canvas.height - relBorderRadius[3]],
        ]
          .filter((d) => d)
          .forEach(([x0, x1, y0, y1]) => {
            ctx.moveTo(x0, y0);
            ctx.quadraticCurveTo(x1, y0, x1, y1);
          });
        ctx.stroke();
      }
    }

    // paint background
    if (this.backgroundColor) {
      ctx.fillStyle = this.backgroundColor;
      if (!this.relBorderRadius) {
        ctx.fillRect(relBorder[0], relBorder[1], canvas.width - relBorder[0] * 2, canvas.height - relBorder[1] * 2);
      } else {
        // fill with rounded corners
        ctx.beginPath();
        ctx.moveTo(relBorder[0], relBorderRadius[0]);
        [
          [relBorder[0], relBorderRadius[0], canvas.width - relBorderRadius[1], relBorder[1], relBorder[1], relBorder[1]], // t
          [canvas.width - relBorder[0], canvas.width - relBorder[0], canvas.width - relBorder[0], relBorder[1], relBorderRadius[1], canvas.height - relBorderRadius[2]], // r
          [canvas.width - relBorder[0], canvas.width - relBorderRadius[2], relBorderRadius[3], canvas.height - relBorder[1], canvas.height - relBorder[1], canvas.height - relBorder[1]], // b
          [relBorder[0], relBorder[0], relBorder[0], canvas.height - relBorder[1], canvas.height - relBorderRadius[3], relBorderRadius[0]], // t
        ].forEach(([x0, x1, x2, y0, y1, y2]) => {
          ctx.quadraticCurveTo(x0, y0, x1, y1);
          ctx.lineTo(x2, y2);
        });
        ctx.closePath();
        ctx.fill();
      }
    }

    ctx.translate(...relBorder);
    ctx.translate(...relPadding);

    // paint text
    ctx.font = font; // Set font again after canvas is resized, as context properties are reset
    ctx.fillStyle = this.color;
    ctx.textBaseline = "bottom";

    if (drawTextStroke) {
      ctx.lineWidth = (this.strokeWidth * relFontSize) / 10;
      ctx.strokeStyle = this.strokeColor;
    }

    lines.forEach((line, index) => {
      const lineX = (innerWidth - ctx.measureText(line).width) / 2;
      const lineY = (index + 1) * textHeight * this.lineHeight + ((1 - this.lineHeight) * textHeight) / 2;
      drawTextStroke && ctx.strokeText(line, lineX, lineY);
      ctx.fillText(line, lineX, lineY);
    });

    this.texture.needsUpdate = true;
    const yScale = textHeight / relFactor / 600;
    
    this.scale.set((yScale * canvas.width) / canvas.height, yScale, 0);
    // this.scale.set(1, canvas.height / canvas.width, 1);
  }

  clone() {
    return new this.constructor(this.text, this.lineHeight, this.color).copy(this);
  }

  copy(source) {
    THREE.Sprite.prototype.copy.call(this, source);

    this.color = source.color;
    this.backgroundColor = source.backgroundColor;
    this.padding = source.padding;
    this.borderWidth = source.borderWidth;
    this.borderColor = source.borderColor;
    this.fontFace = source.fontFace;
    this.fontSize = source.fontSize;
    this.fontWeight = source.fontWeight;
    this.strokeWidth = source.strokeWidth;
    this.strokeColor = source.strokeColor;

    return this;
  }
}

function getTextWidth(ctx, text) {
  return ctx.measureText(text).width * 1.05;
}
function getTextHeight(ctx, text) {
  const metrics = ctx.measureText(text);
  const fontHeight = metrics.fontBoundingBoxAscent + metrics.fontBoundingBoxDescent;
  const actualHeight = metrics.actualBoundingBoxAscent + metrics.actualBoundingBoxDescent;
  return fontHeight || actualHeight || metrics.width;
}
