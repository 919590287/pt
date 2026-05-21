import * as THREE from "three";
import { MAP_EVENT, Layer } from "../index";

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
  pointIcon: null, //require("@/assets/image/point.svg"),
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

  constructor(opt) {
    super(opt);
    const params = Object.assign({}, defaultParams, opt);
    
  }
}
