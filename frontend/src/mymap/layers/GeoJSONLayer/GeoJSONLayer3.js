import * as THREE from "three";
import { Layer, MAP_EVENT } from "@/mymap/index.js";

import GeoJSONLayerWorker from "../worker/GeoJSONLayer3.worker";

// { noProperties: true, noGeomList: true }
export function parserGeoJSON(text, options = {}) {
  // const timeKey = "parserGeoJSON_" + new Date().getTime();
  // console.time(timeKey);
  return new Promise((resolve, reject) => {
    const worker = new GeoJSONLayerWorker();
    worker.onmessage = (event) => {};
    worker.addEventListener("error", (error) => {
      reject(error);
      worker.terminate();
    });

    let textEncoder = new TextEncoder();
    const array = new Int8Array(textEncoder.encode(text));
    worker.postMessage({ key: "setData", time: new Date().getTime(), data: array }, [array.buffer]);

    const workerCode = `
      function getColor(geom){
        return "red"
      }
    `;

    const blob = new Blob([workerCode], { type: "application/javascript" });
    const workerUrl = URL.createObjectURL(blob);
    worker.postMessage({ key: "func", time: new Date().getTime(), data: workerUrl });
  }).catch(console.log);
}

export class ColorBar2D {
  get list() {
    return this._list;
  }

  get min() {
    return this._min;
  }
  get max() {
    return this._max;
  }

  constructor(list) {
    this.canvas = document.createElement("canvas");
    this.canvas.width = 1024 * 8;
    this.canvas.height = 16;
    this.texture = new THREE.CanvasTexture(this.canvas);
    this.updateList(list);
  }

  updateList(list) {
    this._list = (list || []).map((v) => new ColorBar2DItem(v)).sort((a, b) => a.min - b.min);
    if (this.list[0]) {
      const { range = [this.list[0].min, this.list[this.list.length - 1].max] } = this.list[0];
      this._min = range[0];
      this._max = range[1];
    } else {
      this._min = 0;
      this._max = 1;
    }
    this.updateCanvas();
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

  updateCanvas1() {
    const canvas2D = this.canvas;
    const context2D = canvas2D.getContext("2d");
    // 先清空画布
    context2D.clearRect(0, 0, canvas2D.width, canvas2D.height);
    const MIN = this.min;
    const MAX = this.max;
    // console.log(this.list);
    if (MIN < MAX) {
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
    } else {
      for (const { min, max, color, use } of this.list) {
        if (use) {
          context2D.fillStyle = color;
          context2D.fillRect(0, 0, canvas2D.width, canvas2D.height);
          break;
        }
      }
    }
    // console.log(this.canvas.toDataURL("image/png"));
    this.texture.needsUpdate = true;
  }

  updateCanvas() {
    const canvas2D = this.canvas;
    const context2D = canvas2D.getContext("2d");
    // 先清空画布
    context2D.clearRect(0, 0, canvas2D.width, canvas2D.height);
    const MIN = this.min;
    const MAX = this.max;
    // console.log(this.list);
    if (MIN < MAX) {
      const gradient = context2D.createLinearGradient(0, 0, canvas2D.width, 0);
      let gradientLastP = 0;
      for (const { min, max, color, use } of this.list) {
        if (use) {
          let minP = (min - MIN) / (MAX - MIN);
          let maxP = (max - MIN) / (MAX - MIN);

          if (gradientLastP > minP) {
            minP = gradientLastP;
          }
          if (minP < 0) minP = 0;
          if (minP > 1) minP = 1;
          gradient.addColorStop(minP, color);
          gradientLastP = minP;

          if (gradientLastP > maxP) {
            maxP = gradientLastP;
          }
          maxP = maxP - 1 / this.canvas.width;
          if (maxP < 0) maxP = 0;
          if (maxP > 1) maxP = 1;
          gradient.addColorStop(maxP, color);
          gradientLastP = maxP;
        }
      }
      context2D.fillStyle = gradient;
      context2D.fillRect(0, 0, canvas2D.width, canvas2D.height);
    } else {
      for (const { min, max, color, use } of this.list) {
        if (use) {
          context2D.fillStyle = color;
          context2D.fillRect(0, 0, canvas2D.width, canvas2D.height);
          break;
        }
      }
    }
    this.texture.needsUpdate = true;
  }
}

class ColorBar2DItem {
  constructor({ min, max, label, color, use, range }) {
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
    this.range = range;
  }
}

// export const ICON_LIST = Object.values(import.meta.glob("@/assets/icon_traffic/**"));

export const LINE_STYLE = {
  NONE: 1, // 不显示
  SOLID: 2, // 实线
  DASHED: 3, // 虚线
};

export const LINE_WIDTH_STYLE = {
  UNAUTO: 1, // 固定值
  AUTO: 2, // 根据数据值变化
};

const textureLoader = new THREE.TextureLoader();

const defaultParams = {
  zIndex: 100,

  // ******************** 点 ******************** //
  showPoints: true,
  pointAutoSize: 0,
  pointSize: 500,
  pointColor: "#01ae9c", // ffa500
  pointIcon: require("@/assets/image/point.svg"),
  pointValue: "",
  pointColorBar: [],
  pointOpacity: 1,
  // ******************** 线 ******************** //
  showLines: true,
  lineAutoWidth: 0,
  lineWidth: 100,
  lineOffset: 0,
  lineWidthStyle: LINE_WIDTH_STYLE.UNAUTO,
  lineAnimation: 0,
  lineColor: "#01ae9c",
  lineStyle: LINE_STYLE.SOLID,
  lineValue: "",
  lineColorBar: [],
  lineOpacity: 1,
  // ******************** 面 ******************** //
  showPolygons: true,
  polygonColor: "#01ae9c",
  polygonOpacity: 1,
  polygonBorderOpacity: 1,
  polygonBorderAutoWidth: 0,
  polygonBorderWidth: 100,
  polygonBorderColor: "#fff",
  polygonBorderStyle: LINE_STYLE.SOLID,
  polygonValue: "",
  polygonColorBar: [],
  polygonValue3D: "",
  polygonScale3D: 0,
};

export class GeoJSONLayer extends Layer {
  name = "GeoJSONLayer";
  color = new THREE.Color(0xffa500);
  center = [0, 0];
  propertiesLabels = {};
  propertiesList = [];
  geomList = [];

  showPoints = true;
  pointAutoSize = 0;
  pointSize = 1;
  pointColor = new THREE.Color(0xffa500);
  pointTexture = null;
  pointValue = null;
  pointColorBar = new ColorBar2D([]);
  pointOpacity = 1;
  pointMesh = null;
  pointGroup = new THREE.Group();
  pointPLGroup = new THREE.Group();
  pointPMGroup = new THREE.Group();

  showLines = true;
  lineAutoWidth = 0;
  lineWidth = 100;
  lineOffset = 0;
  lineWidthStyle = LINE_WIDTH_STYLE.UNAUTO;
  lineAnimation = 0;
  lineColor = new THREE.Color(0xffa500);
  lineStyle = LINE_STYLE.SOLID;
  lineValue = null;
  lineColorBar = new ColorBar2D([]);
  lineOpacity = 1;
  lineMeshList = [];
  lineGroup = new THREE.Group();
  linePLGroup = new THREE.Group();
  linePMGroup = new THREE.Group();

  showPolygons = true;
  polygonColor = new THREE.Color(0xffa500);
  polygonOpacity = 1;
  polygonBorderOpacity = 1;
  polygonBorderAutoWidth = 0;
  polygonBorderWidth = 1;
  polygonBorderColor = new THREE.Color(0xffa500);
  polygonBorderStyle = LINE_STYLE.SOLID;
  polygonValue = null;
  polygonColorBar = new ColorBar2D([]);
  polygonValue3D = "";
  polygonScale3D = 100;
  polygonMeshList = [];
  polygonBorderMeshList = [];
  polygonGroup = new THREE.Group();
  polygonPLGroup = new THREE.Group();
  polygonPMGroup = new THREE.Group();
  polygonBorderGroup = new THREE.Group();

  setGeoJsonData(res) {
    if (this.isDisposed) return;
    this.propertiesList = res.propertiesList;
    this.geomList = res.geomList;
    this.setCenter(res.center);
    this.setPointArray(res.pointArray);
    this.setLineArray(res.lineArray);
    this.setPolygonArray(res.polygonArray);
    this.setPropertiesLabels(res.propertiesLabels);
  }

  // ******************** 点 ******************** //
  setShowPoints(showPoints) {
    this.showPoints = showPoints;
    if (this.showPoints) {
      this.scene.add(this.pointGroup);
      this.pickLayerScene.add(this.pointPLGroup);
      this.pickMeshScene.add(this.pointPMGroup);
    } else {
      this.pointGroup.removeFromParent();
      this.pointPLGroup.removeFromParent();
      this.pointPMGroup.removeFromParent();
    }
  }
  setPointAutoSize(pointAutoSize) {
    this.pointAutoSize = Number(pointAutoSize || 0);
    if (this.map && this.pointAutoSize > 0) {
      const size = this.pointAutoSize * this.map.plottingScale;
      this.pointMaterial.uniforms.size.value = size;
      this.pointMaterial.needsUpdate = true;
      this.pointPickLayerMaterial.uniforms.size.value = size;
      this.pointPickLayerMaterial.needsUpdate = true;
      this.pointPickItemMaterial.uniforms.size.value = size;
      this.pointPickItemMaterial.needsUpdate = true;
    }
    this.setPointSize(this.pointSize);
  }
  setPointSize(pointSize) {
    this.pointSize = pointSize;
    if (this.pointAutoSize > 0) return;
    this.pointMaterial.uniforms.size.value = this.pointSize;
    this.pointMaterial.needsUpdate = true;
    this.pointPickLayerMaterial.uniforms.size.value = this.pointSize;
    this.pointPickLayerMaterial.needsUpdate = true;
    this.pointPickItemMaterial.uniforms.size.value = this.pointSize;
    this.pointPickItemMaterial.needsUpdate = true;
  }
  setPointColor(pointColor) {
    this.pointColor = new THREE.Color(pointColor);
    this.pointMaterial.uniforms.diffuse.value = this.pointColor;
    this.pointMaterial.needsUpdate = true;
  }
  setPointIcon(pointIcon, pickPointIcon) {
    this.pointIcon = pointIcon;
    this.pointTexture = textureLoader.load(
      pointIcon,
      (data) => {
        // console.log("setPointIcon success", data, pointIcon);
      },
      null,
      (error) => {
        // console.log("setPointIcon error", error, pointIcon);
      },
    );
    this.pointMaterial.defines.USE_MAP = !!this.pointIcon;
    this.pointMaterial.uniforms.map.value = this.pointTexture;
    this.pointMaterial.needsUpdate = true;
  }
  setPointValue(pointValue) {
    this.pointValue = pointValue;
    for (const mesh of this.pointMeshList) {
      mesh.geometry.setValueKey(this.pointValue);
    }
    this.pointMaterial.setColorBar(this.pointColorBar, !!this.pointValue);
    this.pointPickLayerMaterial.setColorBar(this.pointColorBar, false);
    this.pointPickItemMaterial.setColorBar(this.pointColorBar, false);
  }
  setPointColorBar(pointColorBar) {
    this.pointColorBar.updateList(pointColorBar);
    this.pointMaterial.setColorBar(this.pointColorBar, !!this.pointValue);
    this.pointPickLayerMaterial.setColorBar(this.pointColorBar, false);
    this.pointPickItemMaterial.setColorBar(this.pointColorBar, false);
  }
  setPointOpacity(pointOpacity) {
    this.pointOpacity = pointOpacity;

    this.pointMaterial.uniforms.opacity.value = this.pointOpacity;
    this.pointMaterial.needsUpdate = true;
  }
  setPointArray(pointArray) {
    this.pointArray = pointArray;
    this.updatePoint();
  }

  // ******************** 线 ******************** //
  setShowLines(showLines) {
    this.showLines = showLines;
    if (this.showLines) {
      this.scene.add(this.lineGroup);
      this.pickLayerScene.add(this.linePLGroup);
      this.pickMeshScene.add(this.linePMGroup);
    } else {
      this.lineGroup.removeFromParent();
      this.linePLGroup.removeFromParent();
      this.linePMGroup.removeFromParent();
    }
  }
  setLineOffset(lineOffset) {
    this.lineOffset = lineOffset;

    this.lineMaterial.uniforms.lineOffset.value = this.lineOffset;
    this.lineMaterial.needsUpdate = true;
    this.linePickLayerMaterial.uniforms.lineOffset.value = this.lineOffset;
    this.linePickLayerMaterial.needsUpdate = true;
    this.linePickLayerMaterial.uniforms.lineOffset.value = this.lineOffset;
    this.linePickLayerMaterial.needsUpdate = true;
  }
  setLineAutoWidth(lineAutoWidth) {
    this.lineAutoWidth = Number(lineAutoWidth || 0);
    if (this.map && this.lineAutoWidth > 0) {
      const lineWidth = this.lineAutoWidth * this.map.plottingScale;
      this.lineMaterial.uniforms.lineWidth.value = lineWidth;
      this.lineMaterial.needsUpdate = true;
      this.linePickLayerMaterial.uniforms.lineWidth.value = lineWidth;
      this.linePickLayerMaterial.needsUpdate = true;
      this.linePickItemMaterial.uniforms.lineWidth.value = lineWidth;
      this.linePickItemMaterial.needsUpdate = true;
    }
    this.setLineWidth(this.lineWidth);
  }
  setLineWidth(lineWidth) {
    this.lineWidth = lineWidth;
    if (this.lineAutoWidth > 0) return;
    this.lineMaterial.uniforms.lineWidth.value = this.lineWidth;
    this.lineMaterial.needsUpdate = true;
    this.linePickLayerMaterial.uniforms.lineWidth.value = this.lineWidth;
    this.linePickLayerMaterial.needsUpdate = true;
    this.linePickItemMaterial.uniforms.lineWidth.value = this.lineWidth;
    this.linePickItemMaterial.needsUpdate = true;
  }
  setLineWidthStyle(lineWidthStyle) {
    this.lineWidthStyle = lineWidthStyle;

    this.lineMaterial.uniforms.lineWidthStyle.value = this.lineWidthStyle;
    this.lineMaterial.needsUpdate = true;
    this.linePickLayerMaterial.uniforms.lineWidthStyle.value = this.lineWidthStyle;
    this.linePickLayerMaterial.needsUpdate = true;
    this.linePickItemMaterial.uniforms.lineWidthStyle.value = this.lineWidthStyle;
    this.linePickItemMaterial.needsUpdate = true;
  }
  setLineAnimation(lineAnimation) {
    this.lineAnimation = lineAnimation;
    this.lineMaterial.setLineAnimation(lineAnimation);
  }
  setLineColor(lineColor) {
    this.lineColor = new THREE.Color(lineColor);
    this.lineMaterial.uniforms.diffuse.value = this.lineColor;
    this.lineMaterial.needsUpdate = true;
  }
  setLineStyle(lineStyle) {
    this.lineStyle = lineStyle;
    // 如果线段样式是虚线或不显示时需要把深度写入关闭，否在会遮盖下方的物体
    if (this.lineStyle === LINE_STYLE.DASHED || this.lineStyle === LINE_STYLE.NONE) {
      this.lineMaterial.depthWrite = false;
    } else {
      this.lineMaterial.depthWrite = true;
    }
    this.lineMaterial.uniforms.lineStyle.value = this.lineStyle;
    this.lineMaterial.needsUpdate = true;
    this.linePickLayerMaterial.uniforms.lineStyle.value = this.lineStyle;
    this.linePickLayerMaterial.needsUpdate = true;
    this.linePickItemMaterial.uniforms.lineStyle.value = this.lineStyle;
    this.linePickItemMaterial.needsUpdate = true;
  }
  setLineValue(lineValue) {
    this.lineValue = lineValue;
    for (const mesh of this.lineMeshList) {
      mesh.geometry.setValueKey(this.lineValue);
    }
    this.lineMaterial.setColorBar(this.lineColorBar, !!this.lineValue);
    this.linePickLayerMaterial.setColorBar(this.lineColorBar, false);
    this.linePickItemMaterial.setColorBar(this.lineColorBar, false);
  }
  setLineColorBar(lineColorBar) {
    this.lineColorBar.updateList(lineColorBar);
    this.lineMaterial.setColorBar(this.lineColorBar, !!this.lineValue);
    this.linePickLayerMaterial.setColorBar(this.lineColorBar, false);
    this.linePickItemMaterial.setColorBar(this.lineColorBar, false);
  }
  setLineOpacity(lineOpacity) {
    this.lineOpacity = lineOpacity;

    this.lineMaterial.uniforms.opacity.value = this.lineOpacity;
    this.lineMaterial.needsUpdate = true;
  }
  setLineArray(lineArray) {
    this.lineArray = lineArray;
    this.updateLine();
  }

  // ******************** 面 ******************** //
  setShowPolygons(showPolygons) {
    this.showPolygons = showPolygons;
    if (this.showPolygons) {
      this.scene.add(this.polygonGroup);
      this.pickLayerScene.add(this.polygonPLGroup);
      this.pickMeshScene.add(this.polygonPMGroup);
      this.scene.add(this.polygonBorderGroup);
    } else {
      this.polygonGroup.removeFromParent();
      this.polygonPLGroup.removeFromParent();
      this.polygonPMGroup.removeFromParent();
      this.polygonBorderGroup.removeFromParent();
    }
  }
  setPolygonOpacity(polygonOpacity) {
    this.polygonOpacity = polygonOpacity;

    this.polygonMaterial.uniforms.opacity.value = this.polygonOpacity;
    this.polygonMaterial.needsUpdate = true;
    this.polygonBorderMaterial.uniforms.opacity.value = this.polygonOpacity;
    this.polygonBorderMaterial.needsUpdate = true;
  }
  setPolygonBorderOpacity(polygonBorderOpacity) {
    this.polygonBorderOpacity = polygonBorderOpacity;

    this.polygonBorderMaterial.uniforms.opacity.value = this.polygonBorderOpacity;
    this.polygonBorderMaterial.needsUpdate = true;
  }
  setPolygonColor(polygonColor) {
    this.polygonColor = new THREE.Color(polygonColor);

    this.polygonMaterial.uniforms.diffuse.value = this.polygonColor;
    this.polygonMaterial.needsUpdate = true;
  }
  setPolygonBorderAutoWidth(polygonBorderAutoWidth) {
    this.polygonBorderAutoWidth = Number(polygonBorderAutoWidth || 0);
    if (this.map && this.polygonBorderAutoWidth > 0) {
      const lineWidth = this.polygonBorderAutoWidth * this.map.plottingScale;
      this.polygonBorderMaterial.uniforms.lineWidth.value = lineWidth;
      this.polygonBorderMaterial.needsUpdate = true;
    }
    this.setPolygonBorderWidth(this.polygonBorderWidth);
  }
  setPolygonBorderWidth(polygonBorderWidth) {
    this.polygonBorderWidth = polygonBorderWidth;
    if (this.polygonBorderAutoWidth > 0) return;
    this.polygonBorderMaterial.uniforms.lineWidth.value = this.polygonBorderWidth;
    this.polygonBorderMaterial.needsUpdate = true;
  }
  setPolygonBorderColor(polygonBorderColor) {
    this.polygonBorderColor = new THREE.Color(polygonBorderColor);

    this.polygonBorderMaterial.uniforms.diffuse.value = this.polygonBorderColor;
    this.polygonBorderMaterial.needsUpdate = true;
  }
  setPolygonBorderStyle(polygonBorderStyle) {
    this.polygonBorderStyle = polygonBorderStyle;

    this.polygonBorderMaterial.uniforms.lineStyle.value = this.polygonBorderStyle;
    this.polygonBorderMaterial.needsUpdate = true;
  }
  setPolygonValue(polygonValue) {
    this.polygonValue = polygonValue;

    for (const mesh of this.polygonMeshList) {
      mesh.geometry.setValueKey(this.polygonValue);
    }
    for (const mesh of this.polygonBorderMeshList) {
      mesh.geometry.setValueKey(this.polygonValue);
    }

    this.polygonMaterial.setColorBar(this.polygonColorBar, !!this.polygonValue);
    this.polygonPickLayerMaterial.setColorBar(this.polygonColorBar, false);
    this.polygonPickItemMaterial.setColorBar(this.polygonColorBar, false);
    this.polygonBorderMaterial.setColorBar(this.polygonColorBar, !!this.polygonValue);
  }
  setPolygonColorBar(polygonColorBar) {
    this.polygonColorBar.updateList(polygonColorBar);
    this.polygonMaterial.setColorBar(this.polygonColorBar, !!this.polygonValue);
    this.polygonPickLayerMaterial.setColorBar(this.polygonColorBar, false);
    this.polygonPickItemMaterial.setColorBar(this.polygonColorBar, false);
    this.polygonBorderMaterial.setColorBar(this.polygonColorBar, !!this.polygonValue);
  }
  setPolygonValue3D(polygonValue3D) {
    this.polygonValue3D = polygonValue3D;

    for (const mesh of this.polygonMeshList) {
      mesh.geometry.setValue3DKey(this.polygonValue3D);
    }
    for (const mesh of this.polygonBorderMeshList) {
      mesh.geometry.setValue3DKey(this.polygonValue3D);
    }

    const properties = this.propertiesLabels[this.polygonValue3D];
    if (properties) {
      this.polygonMaterial.uniforms.min3DValue.value = properties.min;
      this.polygonMaterial.uniforms.max3DValue.value = properties.max;
      this.polygonPickLayerMaterial.uniforms.min3DValue.value = properties.min;
      this.polygonPickLayerMaterial.uniforms.max3DValue.value = properties.max;
      this.polygonPickItemMaterial.uniforms.min3DValue.value = properties.min;
      this.polygonPickItemMaterial.uniforms.max3DValue.value = properties.max;
      this.polygonBorderMaterial.uniforms.min3DValue.value = properties.min;
      this.polygonBorderMaterial.uniforms.max3DValue.value = properties.max;
      // console.log(properties);
    }

    this.polygonMaterial.defines.USE_3D = !!this.polygonValue3D;
    this.polygonMaterial.needsUpdate = true;
    this.polygonPickLayerMaterial.defines.USE_3D = !!this.polygonValue3D;
    this.polygonPickLayerMaterial.needsUpdate = true;
    this.polygonPickItemMaterial.defines.USE_3D = !!this.polygonValue3D;
    this.polygonPickItemMaterial.needsUpdate = true;

    this.polygonBorderMaterial.defines.USE_3D = !!this.polygonValue3D;
    this.polygonBorderMaterial.needsUpdate = true;
  }
  setPolygonScale3D(polygonScale3D) {
    this.polygonScale3D = polygonScale3D;

    this.polygonMaterial.uniforms.scale3D.value = this.polygonScale3D;
    this.polygonMaterial.needsUpdate = true;
    this.polygonPickLayerMaterial.uniforms.scale3D.value = this.polygonScale3D;
    this.polygonPickLayerMaterial.needsUpdate = true;
    this.polygonPickItemMaterial.uniforms.scale3D.value = this.polygonScale3D;
    this.polygonPickItemMaterial.needsUpdate = true;

    this.polygonBorderMaterial.uniforms.scale3D.value = this.polygonScale3D;
    this.polygonBorderMaterial.needsUpdate = true;
  }
  setPolygonArray(polygonArray) {
    this.polygonArray = polygonArray;
    this.updatePolygon();
  }

  setCenter(center) {
    this.center = center || [0, 0];
    if (this.map) this.on(MAP_EVENT.UPDATE_CENTER, {});
  }

  setPropertiesLabels(propertiesLabels) {
    this.propertiesLabels = propertiesLabels;

    this.setPolygonValue3D(this.polygonValue3D);

    for (const mesh of this.pointMeshList) {
      mesh.geometry.setPropertiesLabels(this.propertiesLabels);
    }
    for (const mesh of this.lineMeshList) {
      mesh.geometry.setPropertiesLabels(this.propertiesLabels);
    }
    for (const mesh of this.polygonMeshList) {
      mesh.geometry.setPropertiesLabels(this.propertiesLabels);
    }
    for (const mesh of this.polygonBorderMeshList) {
      mesh.geometry.setPropertiesLabels(this.propertiesLabels);
    }
  }

  constructor(opt) {
    super(opt);

    const params = Object.assign({}, defaultParams, opt);

    // ******************** 点 ******************** //
    this.pointMaterial = new GeoJSONPointMaterial({
      // side: THREE.DoubleSide,
      transparent: true,
    });
    this.pointPickLayerMaterial = new GeoJSONPointMaterial({
      transparent: false,
      color: this.pickLayerColor,
    });
    this.pointPickItemMaterial = new GeoJSONPointMaterial({
      transparent: false,
      usePickColor: true,
    });
    this.pointMeshList = [];
    this.pointPickLayerMeshList = [];
    this.pointPickItemMeshList = [];

    this.setPointAutoSize(params.pointAutoSize);
    this.setShowPoints(params.showPoints);
    this.setPointSize(params.pointSize);
    this.setPointColor(params.pointColor);
    this.setPointIcon(params.pointIcon);
    this.setPointValue(params.pointValue);
    this.setPointColorBar(params.pointColorBar);
    this.setPointOpacity(params.pointOpacity);

    // ******************** 线 ******************** //
    this.lineMaterial = new GeoJSONLineMaterial({
      transparent: true,
    });
    this.linePickLayerMaterial = new GeoJSONLineMaterial({
      transparent: false,
      color: this.pickLayerColor,
    });
    this.linePickItemMaterial = new GeoJSONLineMaterial({
      transparent: false,
      usePickColor: true,
    });
    this.lineMeshList = [];
    this.linePickLayerMeshList = [];
    this.linePickItemMeshList = [];

    this.setLineAutoWidth(params.lineAutoWidth);
    this.setShowLines(params.showLines);
    this.setLineOffset(params.lineOffset);
    this.setLineWidth(params.lineWidth);
    this.setLineWidthStyle(params.lineWidthStyle);
    this.setLineAnimation(params.lineAnimation);
    this.setLineColor(params.lineColor);
    this.setLineStyle(params.lineStyle);
    this.setLineValue(params.lineValue);
    this.setLineColorBar(params.lineColorBar);
    this.setLineOpacity(params.lineOpacity);

    // ******************** 面 ******************** //
    this.polygonMaterial = new GeoJSONPolygonMaterial({
      transparent: true,
    });
    this.polygonPickLayerMaterial = new GeoJSONPolygonMaterial({
      transparent: false,
      color: this.pickLayerColor,
    });
    this.polygonPickItemMaterial = new GeoJSONPolygonMaterial({
      transparent: false,
      usePickColor: true,
    });
    this.polygonBorderMaterial = new GeoJSONPolygonBorderMaterial({
      transparent: true,
    });
    this.polygonMeshList = [];
    this.polygonPickLayerMeshList = [];
    this.polygonPickItemMeshList = [];
    this.polygonBorderMeshList = [];

    this.setShowPolygons(params.showPolygons);
    this.setPolygonColor(params.polygonColor);
    this.setPolygonOpacity(params.polygonOpacity);
    this.setPolygonBorderOpacity(params.polygonBorderOpacity);
    this.setPolygonBorderAutoWidth(params.polygonBorderAutoWidth);
    this.setPolygonBorderWidth(params.polygonBorderWidth);
    this.setPolygonBorderColor(params.polygonBorderColor);
    this.setPolygonBorderStyle(params.polygonBorderStyle);
    this.setPolygonValue(params.polygonValue);
    this.setPolygonColorBar(params.polygonColorBar);
    this.setPolygonValue3D(params.polygonValue3D);
    this.setPolygonScale3D(params.polygonScale3D);
  }

  // 设置拾取图层颜色
  setPickLayerColor(pickLayerColor) {
    this.pickLayerColor = new THREE.Color(pickLayerColor);

    this.pointPickLayerMaterial.uniforms.diffuse.value = pickLayerColor;
    this.pointPickLayerMaterial.needsUpdate = true;

    this.linePickLayerMaterial.uniforms.diffuse.value = pickLayerColor;
    this.linePickLayerMaterial.needsUpdate = true;

    this.polygonPickLayerMaterial.uniforms.diffuse.value = pickLayerColor;
    this.polygonPickLayerMaterial.needsUpdate = true;
  }

  render() {
    super.render();
    this.lineMaterial.updateAnimation();
  }

  dispose() {
    super.dispose();
    this.clearScene();
  }

  clearScene() {
    this.setPointArray([]);
    this.setLineArray([]);
    this.setPolygonArray([]);
  }

  on(type, data) {
    if (type == MAP_EVENT.UPDATE_CAMERA_HEIGHT || type == MAP_EVENT.UPDATE_RENDERER_SIZE) {
      this.map.nextFrame(() => {
        if (this.pointAutoSize > 0) {
          const size = this.pointAutoSize * this.map.plottingScale;
          this.pointMaterial.uniforms.size.value = size;
          this.pointMaterial.needsUpdate = true;
          this.pointPickLayerMaterial.uniforms.size.value = size;
          this.pointPickLayerMaterial.needsUpdate = true;
          this.pointPickItemMaterial.uniforms.size.value = size;
          this.pointPickItemMaterial.needsUpdate = true;
        }
        if (this.lineAutoWidth > 0) {
          const lineWidth = this.lineAutoWidth * this.map.plottingScale;
          this.lineMaterial.uniforms.lineWidth.value = lineWidth;
          this.lineMaterial.needsUpdate = true;
          this.linePickLayerMaterial.uniforms.lineWidth.value = lineWidth;
          this.linePickLayerMaterial.needsUpdate = true;
          this.linePickItemMaterial.uniforms.lineWidth.value = lineWidth;
          this.linePickItemMaterial.needsUpdate = true;
        }
        if (this.polygonBorderAutoWidth > 0) {
          const lineWidth = this.polygonBorderAutoWidth * this.map.plottingScale;
          this.polygonBorderMaterial.uniforms.lineWidth.value = lineWidth;
          this.polygonBorderMaterial.needsUpdate = true;
        }
      });
    }
    if (type == MAP_EVENT.UPDATE_CENTER) {
      const list = [
        this.pointMeshList,
        this.pointPickLayerMeshList,
        this.pointPickItemMeshList,
        this.lineMeshList,
        this.linePickLayerMeshList,
        this.linePickItemMeshList,
        this.polygonMeshList,
        this.polygonPickLayerMeshList,
        this.polygonPickItemMeshList,
        this.polygonBorderMeshList,
      ];
      for (const item1 of list) {
        for (const mesh of item1) {
          const [x, y] = this.map.WebMercatorToCanvasXY(...this.center);
          mesh.position.set(x, y, mesh.position.z);
        }
      }
    }
    if (type == MAP_EVENT.HANDLE_PICK_LEFT) {
      let pickData = this.getPickItem(data);
      if (pickData) {
        this.handleEventListener(MAP_EVENT.HANDLE_PICK_LEFT, pickData);
      } else {
        this.handleEventListener(MAP_EVENT.HANDLE_NO_PICK, {});
      }
    }

    if (type == MAP_EVENT.HANDLE_MOUSE_MOVE_PICK) {
      let pickData = this.getPickItem(data);
      if (pickData) {
        this.handleEventListener(MAP_EVENT.HANDLE_MOUSE_MOVE_PICK, pickData);
      } else {
        this.handleEventListener(MAP_EVENT.HANDLE_MOUSE_MOVE_NO_PICK, {});
      }
    }
  }

  getPickItem(data) {
    if (data.layerId == this.id && !!this.propertiesList && !!this.geomList) {
      const pickId = data.pickColor;
      const properties = this.propertiesList[data.pickColor] || {};
      const geom = this.geomList[data.pickColor] || {};

      const geomBc = [(geom.br.x + geom.tl.x) / 2 + this.center[0], geom.br.y + this.center[1]];
      return JSON.parse(
        JSON.stringify({
          canvasXY: data.canvasXY,
          webMercatorXY: geomBc,
          // webMercatorXY: data.webMercatorXY,
          windowSize: data.windowSize,
          windowXY: data.windowXY,
          pickId: data.pickColor,
          prop: { ...properties },
          geom: {
            ...geom,
            center: this.center,
            propertiesLabels: {},
          },
          layerId: this.id,
        }),
      );
    } else {
      return null;
    }
  }

  onAdd(map) {
    super.onAdd(map);
    this.on(MAP_EVENT.UPDATE_CENTER, {});
    this.on(MAP_EVENT.UPDATE_CAMERA_HEIGHT, {});
  }

  clearPoint() {
    for (const mesh of this.pointMeshList) {
      mesh.removeFromParent();
      mesh.geometry.dispose();
    }
    this.pointMeshList = [];

    for (const mesh of this.pointPickLayerMeshList) {
      mesh.removeFromParent();
      mesh.geometry.dispose();
    }
    this.pointPickLayerMeshList = [];

    for (const mesh of this.pointPickItemMeshList) {
      mesh.removeFromParent();
      mesh.geometry.dispose();
    }
    this.pointPickItemMeshList = [];
  }

  async updatePoint() {
    this.clearPoint();
    if (!this.pointArray || this.isDisposed) return;
    let cx = 0,
      cy = 0;
    if (this.map) [cx, cy] = this.map.WebMercatorToCanvasXY(this.center[0], this.center[1]);
    const maxPoint = 100000;
    for (let i = 0, l = this.pointArray.length; i < l; i += maxPoint * 3) {
      const pointArray = this.pointArray.slice(i, i + maxPoint * 3 + 1);

      const geometry = new GeoJSONPointListGeometry(pointArray, this.propertiesLabels, this.pointValue);

      const mesh = new THREE.Mesh(geometry, this.pointMaterial);
      mesh.position.set(cx, cy, 0.4);
      this.pointMeshList.push(mesh);
      this.pointGroup.add(mesh);

      const pickLayerMesh = new THREE.Mesh(geometry, this.pointPickLayerMaterial);
      pickLayerMesh.position.set(cx, cy, 0.4);
      this.pointPickLayerMeshList.push(pickLayerMesh);
      this.pointPLGroup.add(pickLayerMesh);

      const pickItemMesh = new THREE.Mesh(geometry, this.pointPickItemMaterial);
      pickItemMesh.position.set(cx, cy, 0.4);
      this.pointPickItemMeshList.push(pickItemMesh);
      this.pointPMGroup.add(pickItemMesh);

      await new Promise((resolve) => setTimeout(resolve, 0));
    }
  }

  clearLine() {
    for (const mesh of this.lineMeshList) {
      mesh.removeFromParent();
      mesh.geometry.dispose();
    }
    this.lineMeshList = [];

    for (const mesh of this.linePickLayerMeshList) {
      mesh.removeFromParent();
      mesh.geometry.dispose();
    }
    this.linePickLayerMeshList = [];

    for (const mesh of this.linePickItemMeshList) {
      mesh.removeFromParent();
      mesh.geometry.dispose();
    }
    this.linePickItemMeshList = [];
  }
  async updateLine() {
    this.clearLine();
    if (!this.lineArray || this.isDisposed) return;
    let cx = 0,
      cy = 0;
    if (this.map) [cx, cy] = this.map.WebMercatorToCanvasXY(this.center[0], this.center[1]);
    const maxLine = 10000000;
    const lineList = [];

    const addMesh = (list) => {
      const geometry = new GeoJSONLineListGeometry(list, this.propertiesLabels, this.lineValue);

      const mesh = new THREE.Mesh(geometry, this.lineMaterial);
      mesh.position.set(cx, cy, 0.3);
      this.lineMeshList.push(mesh);
      this.lineGroup.add(mesh);

      const pickLayerMesh = new THREE.Mesh(geometry, this.linePickLayerMaterial);
      pickLayerMesh.position.set(cx, cy, 0.3);
      this.linePickLayerMeshList.push(pickLayerMesh);
      this.linePLGroup.add(pickLayerMesh);

      const pickItemMesh = new THREE.Mesh(geometry, this.linePickItemMaterial);
      pickItemMesh.position.set(cx, cy, 0.3);
      this.linePickItemMeshList.push(pickItemMesh);
      this.linePMGroup.add(pickItemMesh);

      list.length = 0;
      return new Promise((resolve) => setTimeout(resolve, 0));
    };

    for (let index = 0, l = this.lineArray.length, dataSize = this.lineArray[0], num = 0; index < l; index += 1 + dataSize, dataSize = this.lineArray[index]) {
      const line = this.lineArray.slice(index + 1, index + 1 + dataSize);
      lineList[lineList.length] = line;

      if (index - num > maxLine) {
        num = index;
        await addMesh(lineList);
      }
    }

    if (lineList.length > 0) {
      await addMesh(lineList);
    }
  }

  clearPolygon() {
    for (const mesh of this.polygonMeshList) {
      mesh.removeFromParent();
      mesh.geometry.dispose();
    }
    this.polygonMeshList = [];

    for (const mesh of this.polygonPickLayerMeshList) {
      mesh.removeFromParent();
      mesh.geometry.dispose();
    }
    this.polygonPickLayerMeshList = [];

    for (const mesh of this.polygonPickItemMeshList) {
      mesh.removeFromParent();
      mesh.geometry.dispose();
    }
    this.polygonPickItemMeshList = [];

    for (const mesh of this.polygonBorderMeshList) {
      mesh.removeFromParent();
      mesh.geometry.dispose();
    }
    this.polygonBorderMeshList = [];
  }
  async updatePolygon() {
    this.clearPolygon();
    if (!this.polygonArray || this.isDisposed) return;
    let cx = 0,
      cy = 0;
    if (this.map) [cx, cy] = this.map.WebMercatorToCanvasXY(this.center[0], this.center[1]);
    const maxPolygon = 10000000;
    const polygonList = [];

    const addMesh = (list) => {
      const geometry = new GeoJSONPolygonListGeometry(list, this.propertiesLabels, this.polygonValue, this.polygonValue3D);

      const mesh = new THREE.Mesh(geometry, this.polygonMaterial);
      mesh.position.set(cx, cy, 0.1);
      this.polygonMeshList.push(mesh);
      this.polygonGroup.add(mesh);

      const pickLayerMesh = new THREE.Mesh(geometry, this.polygonPickLayerMaterial);
      pickLayerMesh.position.set(cx, cy, 0.1);
      this.polygonPickLayerMeshList.push(pickLayerMesh);
      this.polygonPLGroup.add(pickLayerMesh);

      const pickItemMesh = new THREE.Mesh(geometry, this.polygonPickItemMaterial);
      pickItemMesh.position.set(cx, cy, 0.1);
      this.polygonPickItemMeshList.push(pickItemMesh);
      this.polygonPMGroup.add(pickItemMesh);

      const borderGeometry = new GeoJSONPolygonBorderListGeometry(list, this.propertiesLabels, this.polygonValue, this.polygonValue3D);

      const borderMesh = new THREE.Mesh(borderGeometry, this.polygonBorderMaterial);
      borderMesh.position.set(cx, cy, 0.2);
      this.polygonBorderMeshList.push(borderMesh);
      this.polygonBorderGroup.add(borderMesh);

      list.length = 0;
      return new Promise((resolve) => setTimeout(resolve, 0));
    };

    for (let index = 0, l = this.polygonArray.length, num = 0, dataSize = this.polygonArray[0]; index < l; index += 1 + dataSize, dataSize = this.polygonArray[index]) {
      const polygon = this.polygonArray.slice(index + 1, index + 1 + dataSize);
      polygonList[polygonList.length] = polygon;
      if (index - num > maxPolygon) {
        num = index;
        await addMesh(polygonList);
      }
    }

    if (polygonList.length > 0) {
      await addMesh(polygonList);
    }
  }
}

export class GeoJSONPointListGeometry extends THREE.BufferGeometry {
  constructor(pointArray = [], propertiesLabels = {}, valueKey = "") {
    super();
    this.type = "GeoJSONPointListGeometry";
    this.isGeoJSONPointListGeometry = true;

    this.valueMap = {};

    const propertiesKeyList = [];

    const attrPosition = new Array();
    const attrPickColor = new Array();
    const attrNormal = new Array();
    const attrSide = new Array();
    const attrValue = new Array();
    const attrIndex = new Array();
    for (let i1 = 0, l1 = Math.floor(pointArray.length / 3); i1 < l1; i1++) {
      // this.addGroup(attrIndex.length, 6, i1);
      const x = pointArray[i1 * 3];
      const y = pointArray[i1 * 3 + 1];
      const value = pointArray[i1 * 3 + 2];
      const pickColor = new THREE.Color(value);
      for (let i2 = 0; i2 < 4; i2++) {
        attrPosition[attrPosition.length] = x;
        attrPosition[attrPosition.length] = y;
        attrPosition[attrPosition.length] = 0;
        attrPickColor[attrPickColor.length] = pickColor.r;
        attrPickColor[attrPickColor.length] = pickColor.g;
        attrPickColor[attrPickColor.length] = pickColor.b;
        attrNormal[attrNormal.length] = 0;
        attrNormal[attrNormal.length] = 0;
        attrNormal[attrNormal.length] = 1;
        attrSide[attrSide.length] = i2;
        attrValue[attrValue.length] = 0;
        propertiesKeyList[propertiesKeyList.length] = value;
      }

      attrIndex[attrIndex.length] = i1 * 4 + 0;
      attrIndex[attrIndex.length] = i1 * 4 + 1;
      attrIndex[attrIndex.length] = i1 * 4 + 3;
      attrIndex[attrIndex.length] = i1 * 4 + 0;
      attrIndex[attrIndex.length] = i1 * 4 + 3;
      attrIndex[attrIndex.length] = i1 * 4 + 2;
    }
    this.setAttribute("position", new THREE.Float32BufferAttribute(attrPosition, 3));
    this.setAttribute("pickColor", new THREE.Float32BufferAttribute(attrPickColor, 3));
    this.setAttribute("normal", new THREE.Float32BufferAttribute(attrNormal, 3));
    this.setAttribute("side", new THREE.Float32BufferAttribute(attrSide, 1));
    this.noValueAttribute = new THREE.Float32BufferAttribute(attrValue, 1);
    this.setAttribute("value", this.noValueAttribute);
    this.setIndex(attrIndex);
    // this.computeVertexNormals();
    this.computeBoundingBox();

    this.propertiesKeyList = propertiesKeyList;
    this.setPropertiesLabels(propertiesLabels);
    this.setValueKey(valueKey);
  }

  setPropertiesLabels(propertiesLabels) {
    try {
      const map = {};
      for (const [label, item] of Object.entries(propertiesLabels)) {
        const l = this.propertiesKeyList.map((v) => Number(item.values[v]));
        map[label] = new THREE.Float32BufferAttribute(l, 1);
      }
      this.valueMap = map;
      this.setValueKey(this._valuekey);
    } catch (error) {
      console.log(error);
      this.valueMap = {};
      this.setValueKey(this._valuekey);
    }
  }

  setValueKey(valueKey) {
    try {
      this._valuekey = valueKey;
      const attrValue = this.valueMap[valueKey];
      if (attrValue) {
        this.setAttribute("value", attrValue);
      } else {
        // mac系统中不能设置空数组，不然图像不显示
        // this.setAttribute("value", new THREE.Float32BufferAttribute([], 1));
        this.setAttribute("value", this.noValueAttribute);
      }
    } catch (error) {
      console.log(error);
    }
  }
}

export class GeoJSONPointMaterial extends THREE.Material {
  constructor(argu) {
    super();
    this.isGeoJSONLineMaterial = true;
    const { color = 0xff0000, opacity = 1, size = 50, map = null, colorBar = null, usePickColor = false, ...params } = argu || {};
    this.alphaTest = 0.2;
    // this.transparent = true;
    this.depthWrite = false;
    this.defines = {
      USE_COLOR_BAR: !!colorBar,
      USE_MAP: !!map,
      USE_PICK_COLOR: usePickColor,
    };
    this.uniforms = {
      diffuse: {
        value: new THREE.Color(color),
      },
      opacity: {
        value: opacity,
      },
      size: {
        value: size,
      },
      map: {
        value: map,
      },
      uvTransform: {
        value: new THREE.Matrix3(),
      },
      colorBar: {
        value: !!colorBar ? colorBar : null,
      },
      minValue: {
        value: !!colorBar ? colorBar.min : 0,
      },
      maxValue: {
        value: !!colorBar ? colorBar.max : 1,
      },
    };
    this.vertexShader = `
      #include <common>
      #include <logdepthbuf_pars_vertex>

      attribute float side;
      attribute float value;
      attribute vec3 pickColor;
      
      varying vec3 vColor;
      varying vec3 vPickColor;
      varying vec2 vUv;
      varying float vValue;

      uniform float size;
      uniform mat3 uvTransform;
      uniform float maxValue;
      uniform float minValue;

      void main() {
        vValue = value;
        vPickColor = pickColor;

        vec3 transformed = vec3(1.0);
        float p = (value - minValue) / (maxValue - minValue) + 0.1;

        // 0 2
        // 1 3 

        if(side == 0.0) {
          transformed.x = position.x - size / 2.0;
          transformed.y = position.y + size / 2.0;
          transformed.z = position.z;
          vUv = ( uvTransform * vec3( 0.0, 1.0, 1.0 ) ).xy;
        } else if(side == 1.0) {
          transformed.x = position.x - size / 2.0;
          transformed.y = position.y - size / 2.0;
          transformed.z = position.z;
          vUv = ( uvTransform * vec3( 0.0, 0.0, 1.0 ) ).xy;
        } else if(side == 2.0) {
          transformed.x = position.x + size / 2.0;
          transformed.y = position.y + size / 2.0;
          transformed.z = position.z;
          vUv = ( uvTransform * vec3( 1.0, 1.0, 1.0 ) ).xy;
        } else if(side == 3.0) {
          transformed.x = position.x + size / 2.0;
          transformed.y = position.y - size / 2.0;
          transformed.z = position.z;
          vUv = ( uvTransform * vec3( 1.0, 0.0, 1.0 ) ).xy;
        }

        gl_Position = projectionMatrix * modelViewMatrix * vec4( transformed.xy, transformed.z + p, 1.0 );

        #include <logdepthbuf_vertex>

      }
    `;
    this.fragmentShader = `
      #include <common>
      #include <logdepthbuf_pars_fragment>


      uniform vec3 diffuse;
      uniform float opacity;
      uniform sampler2D map;
      uniform sampler2D colorBar;
      uniform float maxValue;
      uniform float minValue;
      
      varying vec3 vColor;
      varying vec3 vPickColor;
      varying vec2 vUv;
      varying float vValue;

      void main() {
        vec4 diffuseColor = vec4( diffuse, opacity );
        
        #include <logdepthbuf_fragment>
        
        #ifdef USE_COLOR_BAR
          float p = 0.0;
          if(maxValue != minValue) {
            p = (vValue - minValue) / (maxValue - minValue);
          }
          if(p> 1.0) p = 1.0;
          if(p< 0.0) p = 0.0;
          vec4 barDiffuseColor = texture2D(colorBar, vec2(p , 0.5));
          diffuseColor.rgb = barDiffuseColor.rgb;
          diffuseColor.a *= barDiffuseColor.a;
        #endif

        #ifdef USE_MAP
          vec4 sampledDiffuseColor = texture2D(map, vUv);
          diffuseColor *= sampledDiffuseColor;
        #endif

        #ifdef USE_PICK_COLOR
          diffuseColor = vec4(vPickColor, 1.0);
        #endif

        gl_FragColor = diffuseColor;
      }
    `;
    this.setValues(params);
  }

  setColorBar(colorBar, USE_COLOR_BAR = true) {
    if (colorBar) {
      this.defines.USE_COLOR_BAR = USE_COLOR_BAR && true;
      this.uniforms.colorBar.value = colorBar.texture;
      this.uniforms.minValue.value = colorBar.min;
      this.uniforms.maxValue.value = colorBar.max;
    } else {
      this.defines.USE_COLOR_BAR = USE_COLOR_BAR && false;
      this.uniforms.colorBar.value = null;
      this.uniforms.minValue.value = 0;
      this.uniforms.maxValue.value = 1;
    }
    this.needsUpdate = true;
  }
}

export class GeoJSONLineListGeometry extends THREE.BufferGeometry {
  constructor(lineList = [], propertiesLabels = {}, valueKey = "") {
    super();
    this.type = "GeoJSONLineListGeometry";
    this.isGeoJSONLineListGeometry = true;

    this.valueMap = {};

    const propertiesKeyList = [];

    const attrPosition = new Array();
    const attrPickColor = new Array();
    const attrStartPosition = new Array();
    const attrEndPosition = new Array();
    const attrSide = new Array();
    const attrDistance = new Array();
    const attrValue = new Array();
    const attrIndex = new Array();
    let indexOffset = 0;

    for (let i1 = 0, l1 = lineList.length; i1 < l1; i1++) {
      const value = lineList[i1][0];
      const array = lineList[i1].slice(1);
      const startIndex = attrIndex.length;
      addLine(array, value);
      const endIndex = attrIndex.length - startIndex;
      this.addGroup(startIndex, endIndex, i1);
    }
    this.setAttribute("position", new THREE.Float32BufferAttribute(attrPosition, 3));
    this.setAttribute("pickColor", new THREE.Float32BufferAttribute(attrPickColor, 3));
    this.setAttribute("startPosition", new THREE.Float32BufferAttribute(attrStartPosition, 2));
    this.setAttribute("endPosition", new THREE.Float32BufferAttribute(attrEndPosition, 2));
    this.setAttribute("side", new THREE.Int8BufferAttribute(attrSide, 1));
    this.noValueAttribute = new THREE.Float32BufferAttribute(attrValue, 1);
    this.setAttribute("value", this.noValueAttribute);
    this.setAttribute("distance", new THREE.Float32BufferAttribute(attrDistance, 1));
    this.setIndex(attrIndex);
    this.computeVertexNormals();
    this.computeBoundingBox();

    this.propertiesKeyList = propertiesKeyList;
    this.setPropertiesLabels(propertiesLabels);
    this.setValueKey(valueKey);
    function addLine(array, value) {
      const pickColor = new THREE.Color(value);
      for (let i2 = 0, l2 = array.length / 3; i2 < l2; i2++) {
        let prevX = array[i2 * 3 - 3];
        let prevY = array[i2 * 3 - 2];
        let prevL = array[i2 * 3 - 1];
        let thatX = array[i2 * 3 + 0];
        let thatY = array[i2 * 3 + 1];
        let thatL = array[i2 * 3 + 2];
        let nextX = array[i2 * 3 + 3];
        let nextY = array[i2 * 3 + 4];
        let nextL = array[i2 * 3 + 5];
        if (i2 === 0) {
          prevX = thatX * 2 - nextX;
          prevY = thatY * 2 - nextY;
        }
        if (i2 >= l2 - 1) {
          nextX = thatX * 2 - prevX;
          nextY = thatY * 2 - prevY;
        }

        attrPosition[attrPosition.length] = thatX;
        attrPosition[attrPosition.length] = thatY;
        attrPosition[attrPosition.length] = 0;
        attrPosition[attrPosition.length] = thatX;
        attrPosition[attrPosition.length] = thatY;
        attrPosition[attrPosition.length] = 0;

        attrPickColor[attrPickColor.length] = pickColor.r;
        attrPickColor[attrPickColor.length] = pickColor.g;
        attrPickColor[attrPickColor.length] = pickColor.b;
        attrPickColor[attrPickColor.length] = pickColor.r;
        attrPickColor[attrPickColor.length] = pickColor.g;
        attrPickColor[attrPickColor.length] = pickColor.b;

        attrStartPosition[attrStartPosition.length] = prevX;
        attrStartPosition[attrStartPosition.length] = prevY;
        attrStartPosition[attrStartPosition.length] = prevX;
        attrStartPosition[attrStartPosition.length] = prevY;

        attrEndPosition[attrEndPosition.length] = nextX;
        attrEndPosition[attrEndPosition.length] = nextY;
        attrEndPosition[attrEndPosition.length] = nextX;
        attrEndPosition[attrEndPosition.length] = nextY;

        propertiesKeyList[propertiesKeyList.length] = value;
        propertiesKeyList[propertiesKeyList.length] = value;

        attrValue[attrValue.length] = 0;
        attrValue[attrValue.length] = 0;

        attrDistance[attrDistance.length] = thatL;
        attrDistance[attrDistance.length] = thatL;

        attrSide[attrSide.length] = -1;
        attrSide[attrSide.length] = 1;

        if (i2 < l2 - 1) {
          attrIndex[attrIndex.length] = indexOffset + 0;
          attrIndex[attrIndex.length] = indexOffset + 1;
          attrIndex[attrIndex.length] = indexOffset + 3;
          attrIndex[attrIndex.length] = indexOffset + 0;
          attrIndex[attrIndex.length] = indexOffset + 3;
          attrIndex[attrIndex.length] = indexOffset + 2;
        }
        indexOffset += 2;
      }
    }
  }

  setPropertiesLabels(propertiesLabels) {
    try {
      const map = {};
      for (const [label, item] of Object.entries(propertiesLabels)) {
        const l = this.propertiesKeyList.map((v) => Number(item.values[v]));
        map[label] = new THREE.Float32BufferAttribute(l, 1);
      }
      this.valueMap = map;
      this.setValueKey(this._valuekey);
    } catch (error) {
      console.log(error);
      this.valueMap = {};
      this.setValueKey(this._valuekey);
    }
  }

  setValueKey(valueKey) {
    try {
      this._valuekey = valueKey;
      const attrValue = this.valueMap[valueKey];
      if (attrValue) {
        this.setAttribute("value", attrValue);
      } else {
        // mac系统中不能设置空数组，不然图像不显示
        // this.setAttribute("value", new THREE.Float32BufferAttribute([], 1));
        this.setAttribute("value", this.noValueAttribute);
      }
    } catch (error) {
      console.log(error);
    }
  }
}

export class GeoJSONLineMaterial extends THREE.Material {
  constructor(argu) {
    super();
    this.isGeoJSONLineMaterial = true;
    const { color = 0xff0000, opacity = 1, lineStyle = LINE_STYLE.SOLID, lineWidth = 50, lineWidthStyle = LINE_WIDTH_STYLE.UNAUTO, lineOffset = 0, colorBar = null, usePickColor = false, lineAnimation = 0, ...params } = argu || {};
    // this.alphaTest = 0.1;
    // this.transparent = true;
    // TODO: 暂时关闭深度写入，否则会遮挡其他物体
    // this.depthWrite = false;
    this.defines = {
      USE_COLOR_BAR: !!colorBar,
      USE_PICK_COLOR: usePickColor,
    };
    this.uniforms = {
      diffuse: {
        value: new THREE.Color(color),
      },
      opacity: {
        value: opacity,
      },
      lineStyle: {
        value: lineStyle,
      },
      lineWidth: {
        value: lineWidth,
      },
      lineWidthStyle: {
        value: lineWidthStyle,
      },
      lineOffset: {
        value: lineOffset,
      },
      colorBar: {
        value: !!colorBar ? colorBar : null,
      },
      minValue: {
        value: !!colorBar ? colorBar.min : 0,
      },
      maxValue: {
        value: !!colorBar ? colorBar.max : 1,
      },
      mAnimation: {
        value: 0,
      },
    };
    this.vertexShader = `
      #include <common>
      #include <logdepthbuf_pars_vertex>

      attribute float side;
      attribute float value;
      attribute float distance;
      attribute vec2 startPosition;
      attribute vec2 endPosition;
      attribute vec3 pickColor;
      
      varying vec3 vColor;
      varying vec3 vPickColor;
      varying vec2 vUv;
      varying float vValue;
      varying float vDistance;
      varying float vLineWidth;

      uniform float lineWidth;
      uniform float lineWidthStyle;
      uniform float lineOffset;
      uniform float maxValue;
      uniform float minValue;
      uniform mat3 uvTransform;

      void main() {
        vValue = value;
        vPickColor = pickColor;
        vDistance = distance;
        vLineWidth = lineWidth;

        #ifdef USE_MAP
          vUv = ( uvTransform * vec3( uv, 1 ) ).xy;
        #endif
        
        vec3 transformed = vec3(1.0);

        float offset = lineWidth * 0.5 * side + lineOffset;
        float p = (value - minValue) / (maxValue - minValue) * 0.9 + 0.1;
        
        if(lineWidthStyle == ${Number(LINE_WIDTH_STYLE.AUTO).toFixed(1)}) {
          offset = p * lineWidth * 0.5 * side + lineOffset;
          // 让小的值在上面避免被遮盖
          // p = 1.1 - p;
          vLineWidth = lineWidth * p;
        }

        float lenA = length(position.xy - startPosition);
        float lenB = length(position.xy - endPosition);

        if(lenA == 0. && lenB == 0.) {
          transformed = position;
        } else {
          vec2 dirA = normalize(position.xy - startPosition);
          vec2 dirB = normalize(position.xy - endPosition);

          if(lenA == 0.) {
            float angle = PI * 0.5;
            vec2 normal = vec2(-dirB.y, dirB.x);
            transformed = vec3(position.xy + normal * offset / sin(angle), position.z);
          } else if(lenB == 0.) {
            float angle = PI * 0.5;
            vec2 normal = vec2(dirA.y, -dirA.x);
            transformed = vec3(position.xy + normal * offset / sin(angle), position.z);
          } else {
            vec2 dir = normalize(dirB - dirA);
            vec2 normal = vec2(-dir.y, dir.x);
            float angle = mod(acos(dot(dirB, normal)), 3.14);
            if(angle < 0.2) angle = 0.2;
            if(angle > 2.94) angle = 2.94;
            transformed = vec3(position.xy + normal * offset / sin(angle), position.z);
          }
        }

        gl_Position = projectionMatrix * modelViewMatrix * vec4( transformed.xy, transformed.z + p, 1.0 );

        #include <logdepthbuf_vertex>

      }
    `;
    this.fragmentShader = `
      #include <common>
      #include <logdepthbuf_pars_fragment>


      uniform float lineWidth;
      uniform float lineStyle;

      uniform vec3 diffuse;
      uniform float opacity;
      uniform sampler2D map;
      uniform sampler2D colorBar;
      uniform float maxValue;
      uniform float minValue;
      uniform float mAnimation;
      
      varying vec3 vColor;
      varying vec3 vPickColor;
      varying vec2 vUv;
      varying float vValue;
      varying float vDistance;
      varying float vLineWidth;

      void main() {
        vec4 diffuseColor = vec4( diffuse, opacity );
        
        #include <logdepthbuf_fragment>
        
        #ifdef USE_COLOR_BAR
          float p = 0.0;
          if(maxValue != minValue) {
            p = (vValue - minValue) / (maxValue - minValue);
          }
          if(p> 1.0) p = 1.0;
          if(p< 0.0) p = 0.0;
          vec4 barDiffuseColor = texture2D(colorBar, vec2(p , 0.5));
          diffuseColor.rgb = barDiffuseColor.rgb;
          diffuseColor.a *= barDiffuseColor.a;
        #endif

        if(lineStyle == ${Number(LINE_STYLE.DASHED).toFixed(1)}){
          float dl = mod(vDistance / (vLineWidth * 3.0) + mAnimation, 1.0);
          if(0.50 < dl && dl <= 1.0){
            diffuseColor.a = 0.0;
          }
        } else if(lineStyle == ${Number(LINE_STYLE.NONE).toFixed(1)}){
          diffuseColor.a = 0.0;
        }

        #ifdef USE_PICK_COLOR
          diffuseColor = vec4(vPickColor, 1.0);
        #endif

        gl_FragColor = diffuseColor;

      }
    `;
    this.setValues(params);
    this.setLineAnimation(lineAnimation);
  }

  setLineAnimation(lineAnimation) {
    this.__lineAnimation = lineAnimation;
    this.__ct = new Date().getTime();
    this.updateAnimation();
  }

  setColorBar(colorBar, USE_COLOR_BAR = true) {
    if (!!colorBar) {
      this.defines.USE_COLOR_BAR = USE_COLOR_BAR && true;
      this.uniforms.colorBar.value = colorBar.texture;
      this.uniforms.minValue.value = colorBar.min;
      this.uniforms.maxValue.value = colorBar.max;
    } else {
      this.defines.USE_COLOR_BAR = USE_COLOR_BAR && false;
      this.uniforms.colorBar.value = null;
      this.uniforms.minValue.value = 0;
      this.uniforms.maxValue.value = 1;
    }
    this.needsUpdate = true;
  }

  updateAnimation() {
    if (this.__lineAnimation <= 0) return;
    const now = new Date().getTime();
    const p = ((now - this.__ct) % this.__lineAnimation) / this.__lineAnimation;
    this.uniforms.mAnimation.value = p;
    this.needsUpdate = true;
  }
}

export class GeoJSONPolygonListGeometry extends THREE.BufferGeometry {
  constructor(polygonList = [], propertiesLabels = {}, valueKey = "", value3DKey = "") {
    super();
    this.type = "GeoJSONPolygonListGeometry";
    this.isGeoJSONPolygonListGeometry = true;

    this.valueMap = {};

    const propertiesKeyList = [];

    // buffers
    const indices = [];
    const vertices = [];
    const pickColors = [];
    const normals = [];
    const uvs = [];
    const values = [];
    for (let i = 0; i < polygonList.length; i++) {
      const { shape, value } = getShape(polygonList[i]);
      const startIndex = indices.length;
      addShape(shape, value);
      const endIndex = indices.length - startIndex;
      this.addGroup(startIndex, endIndex, i);
    }
    // build geometry
    this.setIndex(indices);
    this.setAttribute("position", new THREE.Float32BufferAttribute(vertices, 3));
    this.setAttribute("pickColor", new THREE.Float32BufferAttribute(pickColors, 3));
    // this.setAttribute('normal', new THREE.Float32BufferAttribute(normals, 3));
    this.setAttribute("uv", new THREE.Float32BufferAttribute(uvs, 2));
    this.noValueAttribute = new THREE.Float32BufferAttribute(values, 1);
    this.setAttribute("value", this.noValueAttribute);
    this.computeVertexNormals();
    this.computeBoundingBox();
    // helper functions
    function addShape(shape, value) {
      const pickColor = new THREE.Color(value);

      const points = shape.extractPoints(12);

      let shapeVertices = points.shape;
      const shapeHoles = points.holes;

      // 把所有路径变成顺时针
      if (THREE.ShapeUtils.isClockWise(shapeVertices) === false) {
        shapeVertices = shapeVertices.reverse();
      }

      for (let i = 0, l = shapeHoles.length; i < l; i++) {
        const shapeHole = shapeHoles[i];
        if (THREE.ShapeUtils.isClockWise(shapeHole) === true) {
          shapeHoles[i] = shapeHole.reverse();
        }
      }
      // 根据路径和空洞生成面
      const faces = THREE.ShapeUtils.triangulateShape(shapeVertices, shapeHoles);
      for (let i = 0, l = shapeHoles.length; i < l; i++) {
        const shapeHole = shapeHoles[i];
        shapeVertices = shapeVertices.concat(shapeHole);
      }

      // 添加底面
      // {
      //   const indicesOffset = vertices.length / 3;
      //   // 添加点，法向量，uv，保存点对应的valueKey
      //   for (let i = 0, l = shapeVertices.length; i < l; i++) {
      //     const vertex = shapeVertices[i];
      //     vertices.push(vertex.x, vertex.y, 0);
      //     pickColors.push(pickColor.r, pickColor.g, pickColor.b);
      //     // normals.push(0, 0, 1);
      //     uvs.push(vertex.x, vertex.y); // world uvs

      //     propertiesKeyList.push(value);
      //     values.push(0);
      //   }
      //   // 添加面
      //   for (let i = 0, l = faces.length; i < l; i++) {
      //     const face = faces[i];
      //     const a = face[0] + indicesOffset;
      //     const b = face[1] + indicesOffset;
      //     const c = face[2] + indicesOffset;
      //     indices.push(a, b, c);
      //   }
      // }

      // 添加顶面
      {
        const indicesOffset = vertices.length / 3;
        // 添加点，法向量，uv，保存点对应的valueKey
        for (let i = 0, l = shapeVertices.length; i < l; i++) {
          const vertex = shapeVertices[i];
          vertices.push(vertex.x, vertex.y, 1);
          pickColors.push(pickColor.r, pickColor.g, pickColor.b);
          // normals.push(0, 0, 1);
          uvs.push(vertex.x, vertex.y); // world uvs

          propertiesKeyList.push(value);
          values.push(0);
        }
        // 添加面
        for (let i = 0, l = faces.length; i < l; i++) {
          const face = faces[i];
          const a = face[0] + indicesOffset;
          const b = face[1] + indicesOffset;
          const c = face[2] + indicesOffset;
          indices.push(a, b, c);
        }
      }

      // 添加外墙壁
      {
        const indicesOffset = vertices.length / 3;
        const shapeList = [...points.shape, points.shape[0]];
        for (let i = 0, l = shapeList.length - 1; i < l; i++) {
          const vertex1 = shapeList[i];
          const vertex2 = shapeList[i + 1];

          vertices.push(vertex1.x, vertex1.y, 0);
          vertices.push(vertex1.x, vertex1.y, 1);
          vertices.push(vertex2.x, vertex2.y, 0);
          vertices.push(vertex2.x, vertex2.y, 1);

          pickColors.push(pickColor.r, pickColor.g, pickColor.b);
          pickColors.push(pickColor.r, pickColor.g, pickColor.b);
          pickColors.push(pickColor.r, pickColor.g, pickColor.b);
          pickColors.push(pickColor.r, pickColor.g, pickColor.b);

          propertiesKeyList.push(value);
          propertiesKeyList.push(value);
          propertiesKeyList.push(value);
          propertiesKeyList.push(value);

          values.push(0);
          values.push(0);
          values.push(0);
          values.push(0);

          // normals.push(0, 0, -1);
          // normals.push(0, 0, -1);
          // normals.push(0, 0, -1);
          // normals.push(0, 0, -1);
          uvs.push(vertex1.x, vertex1.y); // world uvs
          uvs.push(vertex1.x, vertex1.y); // world uvs
          uvs.push(vertex2.x, vertex2.y); // world uvs
          uvs.push(vertex2.x, vertex2.y); // world uvs

          const a = i * 4 + indicesOffset;
          const b = i * 4 + 1 + indicesOffset;
          const c = i * 4 + 2 + indicesOffset;
          const d = i * 4 + 3 + indicesOffset;

          indices.push(a, b, c);
          indices.push(c, b, d);
        }
      }

      // 添加孔洞墙壁
      for (const holes of points.holes) {
        const indicesOffset = vertices.length / 3;
        const holeList = [...holes, holes[0]];
        for (let i = 0, l = holeList.length - 1; i < l; i++) {
          const vertex1 = holeList[i];
          const vertex2 = holeList[i + 1];

          vertices.push(vertex1.x, vertex1.y, 0);
          vertices.push(vertex1.x, vertex1.y, 1);
          vertices.push(vertex2.x, vertex2.y, 0);
          vertices.push(vertex2.x, vertex2.y, 1);

          pickColors.push(pickColor.r, pickColor.g, pickColor.b);
          pickColors.push(pickColor.r, pickColor.g, pickColor.b);
          pickColors.push(pickColor.r, pickColor.g, pickColor.b);
          pickColors.push(pickColor.r, pickColor.g, pickColor.b);

          propertiesKeyList.push(value);
          propertiesKeyList.push(value);
          propertiesKeyList.push(value);
          propertiesKeyList.push(value);

          values.push(0);
          values.push(0);
          values.push(0);
          values.push(0);

          // normals.push(0, 0, -1);
          // normals.push(0, 0, -1);
          // normals.push(0, 0, -1);
          // normals.push(0, 0, -1);
          uvs.push(vertex1.x, vertex1.y); // world uvs
          uvs.push(vertex1.x, vertex1.y); // world uvs
          uvs.push(vertex2.x, vertex2.y); // world uvs
          uvs.push(vertex2.x, vertex2.y); // world uvs

          const a = i * 4 + indicesOffset;
          const b = i * 4 + 1 + indicesOffset;
          const c = i * 4 + 2 + indicesOffset;
          const d = i * 4 + 3 + indicesOffset;

          indices.push(a, b, c);
          indices.push(c, b, d);
        }
      }
    }

    function getShape(array) {
      const value = array[0];
      const shapeArray = array.slice(1);
      let shape = null;
      for (let j = 0, l = shapeArray.length, size = shapeArray[0]; j < l; j += 1 + size, size = shapeArray[j]) {
        const v = shapeArray.slice(j + 1, j + 1 + size);
        const points = [];
        for (let k = 0, l3 = v.length / 2; k < l3; k++) {
          points[points.length] = new THREE.Vector2(v[k * 2 + 0], v[k * 2 + 1]);
        }
        if (!shape) {
          shape = new THREE.Shape(points);
        } else {
          const holePath = new THREE.Path(points);
          shape.holes.push(holePath);
        }
      }
      return { shape, value };
    }

    this.propertiesKeyList = propertiesKeyList;
    this.setPropertiesLabels(propertiesLabels);
    this.setValueKey(valueKey);
    this.setValue3DKey(value3DKey);
  }

  setPropertiesLabels(propertiesLabels) {
    try {
      const map = {};
      for (const [label, item] of Object.entries(propertiesLabels)) {
        const l = this.propertiesKeyList.map((v) => Number(item.values[v]));
        map[label] = new THREE.Float32BufferAttribute(l, 1);
      }
      this.valueMap = map;
      this.setValueKey(this._valuekey);
      this.setValue3DKey(this._value3Dkey);
    } catch (error) {
      console.log(error);
      this.valueMap = {};
      this.setValueKey(this._valuekey);
      this.setValue3DKey(this._value3Dkey);
    }
  }

  setValueKey(valueKey) {
    try {
      this._valuekey = valueKey;
      const attrValue = this.valueMap[valueKey];
      if (attrValue) {
        this.setAttribute("value", attrValue);
      } else {
        // mac系统中不能设置空数组，不然图像不显示
        // this.setAttribute("value", new THREE.Float32BufferAttribute([], 1));
        this.setAttribute("value", this.noValueAttribute);
      }
    } catch (error) {
      console.log(error);
    }
  }

  setValue3DKey(value3DKey) {
    try {
      this._value3Dkey = value3DKey;
      const attrValue = this.valueMap[value3DKey];
      if (attrValue) {
        this.setAttribute("value3D", attrValue);
      } else {
        // mac系统中不能设置空数组，不然图像不显示
        // this.setAttribute("value", new THREE.Float32BufferAttribute([], 1));
        this.setAttribute("value3D", this.noValueAttribute);
      }
    } catch (error) {
      console.log(error);
    }
  }
}

export class GeoJSONPolygonMaterial extends THREE.Material {
  constructor(argu) {
    super();
    this.isGeoJSONLineMaterial = true;
    const { color = 0xff0000, opacity = 1, size = 50, colorBar = null, usePickColor = false, ...params } = argu || {};
    this.defines = {
      USE_COLOR_BAR: !!colorBar,
      USE_3D: false,
      USE_PICK_COLOR: usePickColor,
    };
    this.uniforms = {
      diffuse: {
        value: new THREE.Color(color),
      },
      opacity: {
        value: opacity,
      },
      size: {
        value: size,
      },
      colorBar: {
        value: !!colorBar ? colorBar : null,
      },
      minValue: {
        value: !!colorBar ? colorBar.min : 0,
      },
      maxValue: {
        value: !!colorBar ? colorBar.max : 1,
      },
      min3DValue: {
        value: 0,
      },
      max3DValue: {
        value: 1,
      },
      scale3D: {
        value: 1,
      },
    };
    this.vertexShader = `
      #include <common>
      #include <logdepthbuf_pars_vertex>


      attribute float value;
      attribute float value3D;
      attribute vec3 pickColor;
      
      varying vec3 vColor;
      varying vec3 vPickColor;
      varying vec2 vUv;
      varying float vValue;

      uniform float size;
      uniform mat3 uvTransform;
      uniform float maxValue;
      uniform float minValue;
      uniform float max3DValue;
      uniform float min3DValue;

      uniform float scale3D;

      void main() {
        vValue = value;
        vPickColor = pickColor;

        vec3 transformed = position;
        float p = (value - minValue) / (maxValue - minValue) + 0.1;
        
        #ifdef USE_3D
          float p3D = (value3D - min3DValue) / (max3DValue - min3DValue);
          transformed.z *= p3D * scale3D;
        #else
          transformed.z = 0.0;
        #endif

        gl_Position = projectionMatrix * modelViewMatrix * vec4( transformed.xy, transformed.z + p, 1.0 );

        #include <logdepthbuf_vertex>

      }
    `;
    this.fragmentShader = `
      #include <common>
      #include <logdepthbuf_pars_fragment>

      uniform vec3 diffuse;
      uniform float opacity;
      uniform sampler2D map;
      uniform sampler2D colorBar;
      uniform float maxValue;
      uniform float minValue;
      
      varying vec3 vColor;
      varying vec3 vPickColor;
      varying vec2 vUv;
      varying float vValue;

      void main() {
        vec4 diffuseColor = vec4( diffuse, opacity );
        
        #include <logdepthbuf_fragment>
        
        #ifdef USE_COLOR_BAR
          float p = 0.0;
          if(maxValue != minValue) {
            p = (vValue - minValue) / (maxValue - minValue);
          }
          if(p > 1.0) p = 1.0;
          if(p < 0.0) p = 0.0;
          vec4 barDiffuseColor = texture2D(colorBar, vec2(p , 0.5));
          diffuseColor.rgb = barDiffuseColor.rgb;
          diffuseColor.a *= barDiffuseColor.a;
        #endif
        
        #ifdef USE_PICK_COLOR
          diffuseColor = vec4(vPickColor, 1.0);
        #endif

        gl_FragColor = diffuseColor;
      }
    `;
    this.setValues(params);
  }

  setColorBar(colorBar, USE_COLOR_BAR = true) {
    if (!!colorBar) {
      this.defines.USE_COLOR_BAR = USE_COLOR_BAR && true;
      this.uniforms.colorBar.value = colorBar.texture;
      this.uniforms.minValue.value = colorBar.min;
      this.uniforms.maxValue.value = colorBar.max;
    } else {
      this.defines.USE_COLOR_BAR = USE_COLOR_BAR && false;
      this.uniforms.colorBar.value = null;
      this.uniforms.minValue.value = 0;
      this.uniforms.maxValue.value = 1;
    }
    this.needsUpdate = true;
  }
}

export class GeoJSONPolygonBorderListGeometry extends THREE.BufferGeometry {
  constructor(polygonList, propertiesLabels, valueKey, value3DKey) {
    super();
    this.type = "GeoJSONPolygonBorderListGeometry";
    this.isGeoJSONPolygonBorderListGeometry = true;

    this.valueMap = {};

    const propertiesKeyList = [];

    const attrPosition = new Array();
    const attrStartPosition = new Array();
    const attrEndPosition = new Array();
    const attrSide = new Array();
    const attrDistance = new Array();
    const attrValue = new Array();
    const attrIndex = new Array();
    let indexOffset = 0;

    for (let i = 0; i < polygonList.length; i++) {
      const { shape, value } = getShape(polygonList[i]);
      const points = shape.extractPoints(12);
      let shapeVertices = points.shape;
      const shapeHoles = points.holes;
      // 把所有路径变成顺时针
      if (THREE.ShapeUtils.isClockWise(shapeVertices) === false) {
        shapeVertices = shapeVertices.reverse();
      }
      addLine(shapeVertices, value);

      for (let i = 0, l = shapeHoles.length; i < l; i++) {
        let shapeHole = shapeHoles[i];
        if (THREE.ShapeUtils.isClockWise(shapeHole) === true) {
          shapeHole = shapeHole.reverse();
        }
        addLine(shapeHole, value);
      }
    }

    this.setAttribute("position", new THREE.Float32BufferAttribute(attrPosition, 3));
    this.setAttribute("startPosition", new THREE.Float32BufferAttribute(attrStartPosition, 2));
    this.setAttribute("endPosition", new THREE.Float32BufferAttribute(attrEndPosition, 2));
    this.setAttribute("side", new THREE.Int8BufferAttribute(attrSide, 1));
    this.noValueAttribute = new THREE.Float32BufferAttribute(attrValue, 1);
    this.setAttribute("value", this.noValueAttribute);
    this.setAttribute("value3D", this.noValueAttribute);
    this.setAttribute("distance", new THREE.Float32BufferAttribute(attrDistance, 1));
    this.setIndex(attrIndex);
    this.computeVertexNormals();
    this.computeBoundingBox();

    this.propertiesKeyList = propertiesKeyList;
    this.setPropertiesLabels(propertiesLabels);
    this.setValueKey(valueKey);
    this.setValue3DKey(value3DKey);

    function addLine(points, value) {
      let distance = 0;
      for (let i2 = 0, l2 = points.length; i2 < l2; i2++) {
        let prev = points[i2 - 1];
        let that = points[i2];
        let next = points[i2 + 1];
        if (i2 === 0) {
          prev = that.clone().multiplyScalar(2).sub(next);
        }
        if (i2 >= l2 - 1) {
          next = that.clone().multiplyScalar(2).sub(prev);
        }

        attrPosition[attrPosition.length] = that.x;
        attrPosition[attrPosition.length] = that.y;
        attrPosition[attrPosition.length] = 1;
        attrPosition[attrPosition.length] = that.x;
        attrPosition[attrPosition.length] = that.y;
        attrPosition[attrPosition.length] = 1;

        attrStartPosition[attrStartPosition.length] = prev.x;
        attrStartPosition[attrStartPosition.length] = prev.y;
        attrStartPosition[attrStartPosition.length] = prev.x;
        attrStartPosition[attrStartPosition.length] = prev.y;

        attrEndPosition[attrEndPosition.length] = next.x;
        attrEndPosition[attrEndPosition.length] = next.y;
        attrEndPosition[attrEndPosition.length] = next.x;
        attrEndPosition[attrEndPosition.length] = next.y;

        propertiesKeyList[propertiesKeyList.length] = value;
        propertiesKeyList[propertiesKeyList.length] = value;

        attrValue[attrValue.length] = 0;
        attrValue[attrValue.length] = 0;

        attrDistance[attrDistance.length] = distance;
        attrDistance[attrDistance.length] = distance;

        attrSide[attrSide.length] = -1;
        attrSide[attrSide.length] = 1;

        if (i2 < l2 - 1) {
          attrIndex[attrIndex.length] = indexOffset + 0;
          attrIndex[attrIndex.length] = indexOffset + 1;
          attrIndex[attrIndex.length] = indexOffset + 3;
          attrIndex[attrIndex.length] = indexOffset + 0;
          attrIndex[attrIndex.length] = indexOffset + 3;
          attrIndex[attrIndex.length] = indexOffset + 2;
        }
        indexOffset += 2;

        distance += that.distanceTo(next);
      }
    }

    function getShape(array) {
      const value = array[0];
      const shapeArray = array.slice(1);
      let shape = null;
      for (let j = 0, l = shapeArray.length, size = shapeArray[0]; j < l; j += 1 + size, size = shapeArray[j]) {
        const v = shapeArray.slice(j + 1, j + 1 + size);
        const points = [];
        for (let k = 0, l3 = v.length / 2; k < l3; k++) {
          points[points.length] = new THREE.Vector2(v[k * 2 + 0], v[k * 2 + 1]);
        }
        if (!shape) {
          shape = new THREE.Shape(points);
        } else {
          const holePath = new THREE.Path(points);
          shape.holes.push(holePath);
        }
      }
      return { shape, value };
    }
  }

  setPropertiesLabels(propertiesLabels) {
    try {
      const map = {};
      for (const [label, item] of Object.entries(propertiesLabels)) {
        const l = this.propertiesKeyList.map((v) => Number(item.values[v]));
        map[label] = new THREE.Float32BufferAttribute(l, 1);
      }
      this.valueMap = map;
      this.setValueKey(this._valuekey);
      this.setValue3DKey(this._value3Dkey);
    } catch (error) {
      console.log(error);
      this.valueMap = {};
      this.setValueKey(this._valuekey);
      this.setValue3DKey(this._value3Dkey);
    }
  }

  setValueKey(valueKey) {
    try {
      this._valuekey = valueKey;
      const attrValue = this.valueMap[valueKey];
      if (attrValue) {
        this.setAttribute("value", attrValue);
      } else {
        // mac系统中不能设置空数组，不然图像不显示
        // this.setAttribute("value", new THREE.Float32BufferAttribute([], 1));
        this.setAttribute("value", this.noValueAttribute);
      }
    } catch (error) {
      console.log(error);
    }
  }

  setValue3DKey(value3DKey) {
    try {
      this._value3Dkey = value3DKey;
      const attrValue = this.valueMap[value3DKey];
      if (attrValue) {
        this.setAttribute("value3D", attrValue);
      } else {
        // mac系统中不能设置空数组，不然图像不显示
        // this.setAttribute("value", new THREE.Float32BufferAttribute([], 1));
        this.setAttribute("value3D", this.noValueAttribute);
      }
    } catch (error) {
      console.log(error);
    }
  }
}

export class GeoJSONPolygonBorderMaterial extends THREE.Material {
  constructor(argu) {
    super();
    this.isGeoJSONPolygonBorderMaterial = true;
    this.side = THREE.DoubleSide;
    const { color = 0xff0000, opacity = 1, lineStyle = LINE_STYLE.SOLID, lineWidth = 50, lineOffset = 0, colorBar = null, ...params } = argu || {};
    this.defines = {
      USE_COLOR_BAR: !!colorBar,
      USE_3D: false,
    };
    this.uniforms = {
      diffuse: {
        value: new THREE.Color(color),
      },
      opacity: {
        value: opacity,
      },
      lineStyle: {
        value: lineStyle,
      },
      lineWidth: {
        value: lineWidth,
      },
      lineOffset: {
        value: lineOffset,
      },
      colorBar: {
        value: !!colorBar ? colorBar : null,
      },
      minValue: {
        value: !!colorBar ? colorBar.min : 0,
      },
      maxValue: {
        value: !!colorBar ? colorBar.max : 1,
      },
      min3DValue: {
        value: 0,
      },
      max3DValue: {
        value: 1,
      },
      scale3D: {
        value: 100,
      },
    };
    this.vertexShader = `
      #include <common>
      #include <logdepthbuf_pars_vertex>

      attribute float side;
      attribute float value;
      attribute float value3D;
      attribute float distance;
      attribute vec2 startPosition;
      attribute vec2 endPosition;
      
      varying vec3 vColor;
      varying vec2 vUv;
      varying float vValue;
      varying float vDistance;

      uniform float lineWidth;
      uniform float lineOffset;
      uniform float maxValue;
      uniform float minValue;
      uniform float max3DValue;
      uniform float min3DValue;

      uniform float scale3D;

      void main() {
        vValue = value;
        vDistance = distance;
        
        #ifdef USE_MAP
          vUv = ( uvTransform * vec3( uv, 1 ) ).xy;
        #endif
        
        vec3 transformed = vec3(1.0);

        float offset = lineWidth / 2.0 * side + lineOffset;
        float p = (value - minValue) / (maxValue - minValue) + 0.1;

        float lenA = length(position.xy - startPosition);
        float lenB = length(position.xy - endPosition);

        if(lenA == 0. && lenB == 0.) {
          transformed = position;
        } else {
          vec2 dirA = normalize(position.xy - startPosition);
          vec2 dirB = normalize(position.xy - endPosition);

          if(lenA == 0.) {
            float angle = PI / 2.0;
            vec2 normal = vec2(-dirB.y, dirB.x);
            transformed = vec3(position.xy + normal * offset / sin(angle), position.z);
          } else if(lenB == 0.) {
            float angle = PI / 2.0;
            vec2 normal = vec2(dirA.y, -dirA.x);
            transformed = vec3(position.xy + normal * offset / sin(angle), position.z);
          } else {
            vec2 dir = normalize(dirB - dirA);
            vec2 normal = vec2(-dir.y, dir.x);
            float angle = mod(acos(dot(dirB, normal)), 3.14);
            if(angle < 0.2) angle = 0.2;
            if(angle > 2.94) angle = 2.94;
            transformed = vec3(position.xy + normal * offset / sin(angle), position.z);
          }
        }
          
        #ifdef USE_3D
          float p3D = (value3D - min3DValue) / (max3DValue - min3DValue);
          transformed.z *= p3D * scale3D;
        #else
          transformed.z = 0.0;
        #endif

        gl_Position = projectionMatrix * modelViewMatrix * vec4( transformed.xy, transformed.z + p, 1.0 );

        #include <logdepthbuf_vertex>

      }
    `;
    this.fragmentShader = `
      #include <common>
      #include <logdepthbuf_pars_fragment>


      uniform float lineWidth;
      uniform float lineStyle;

      uniform vec3 diffuse;
      uniform float opacity;
      uniform sampler2D map;
      uniform float maxValue;
      uniform float minValue;
      
      varying vec3 vColor;
      varying vec2 vUv;
      varying float vValue;
      varying float vDistance;

      void main() {
        vec4 diffuseColor = vec4( diffuse, opacity );
        
        #include <logdepthbuf_fragment>

        if(lineStyle == ${Number(LINE_STYLE.DASHED).toFixed(1)}){
          float dl = mod(vDistance / (lineWidth * 3.0), 1.0);
          dl += vUv.y;
          if(0.50 < dl && dl <= 1.0){
            diffuseColor.a = 0.0;
          }
        } else if(lineStyle == ${Number(LINE_STYLE.NONE).toFixed(1)}){
          diffuseColor.a = 0.0;
        }

        gl_FragColor = diffuseColor;
      }
    `;
    this.setValues(params);
  }

  setColorBar(colorBar, USE_COLOR_BAR = true) {
    if (!!colorBar) {
      this.defines.USE_COLOR_BAR = USE_COLOR_BAR && true;
      this.uniforms.colorBar.value = colorBar.texture;
      this.uniforms.minValue.value = colorBar.min;
      this.uniforms.maxValue.value = colorBar.max;
    } else {
      this.defines.USE_COLOR_BAR = USE_COLOR_BAR && false;
      this.uniforms.colorBar.value = null;
      this.uniforms.minValue.value = 0;
      this.uniforms.maxValue.value = 1;
    }
    this.needsUpdate = true;
  }
}
