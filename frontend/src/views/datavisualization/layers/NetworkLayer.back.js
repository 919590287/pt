import * as THREE from "three";
import { Layer, MAP_EVENT } from "@/mymap/index.js";

import { getTileNetwork } from "@/api/index.js";
import { guid } from "@/utils/utils";

const BUILD_ZOOM = 12;
const EARTH_RADIUS = 20037508.3427892;

export class NetworkLayer extends Layer {
  _noLoadTileList = [];
  colors = ColorBar2DColors;
  lineWidth = 6;
  lineOffset = 0;
  time = 0;

  material = null;
  pickLayerMaterial = null;
  pickMaterial = null;
  loadingNum = 0;

  tileMap = {};

  pickColorOffset = 0;

  constructor(opt) {
    super(opt);

    this.time = opt.time || this.time;
    this.colors = opt.colors || this.colors;

    this.lineWidth = opt.lineWidth || this.lineWidth;
    this.lineOffset = opt.lineOffset || this.lineOffset;
    this.showNode = opt.showNode || this.showNode;
    this.showVideoIcon = opt.showVideoIcon || this.showVideoIcon;
    this.videoIconWidth = opt.videoIconWidth || this.videoIconWidth;
    this.selectLine = {
      show: false,
      tile: null,
      line: null,
      center: [0, 0],
      mesh: new THREE.Mesh(
        new THREE.BufferGeometry(),
        new NetworkMaterial({
          color: 0xffa500,
          lineWidth: this.lineWidth,
          lineOffset: this.lineOffset,
        })
      ),
    };
  }

  setSelectLine(lineId) {
    for (const tile of Object.values(this.tileMap)) {
      const lineItem = tile.getLineById(lineId);
      if (lineItem) {
        this.selectLine.tile = tile;
        this.selectLine.line = lineItem;
        this.selectLine.show = true;
        const geometry = new NetworkGeometry([lineItem], 0);
        this.selectLine.mesh.geometry = geometry;
        this.loadMesh();
        break;
      }
    }
  }

  setShowNode(showNode) {
    this.showNode = showNode;
    for (const tile of Object.values(this.tileMap)) {
      tile.setShowNode(showNode);
    }
  }
  setShowVideoIcon(showVideoIcon) {
    this.showVideoIcon = showVideoIcon;
    for (const tile of Object.values(this.tileMap)) {
      tile.setShowVideoIcon(showVideoIcon);
    }
  }

  setTime(time) {
    this.time = time;
    for (const tile of Object.values(this.tileMap)) {
      tile.setTime(time);
    }
  }

  setColors(colors) {
    this.colors = colors || ColorBar2DColors;
    for (const tile of Object.values(this.tileMap)) {
      tile.setColors(colors);
    }
  }

  setVideoIconWidth(videoIconWidth) {
    this.videoIconWidth = videoIconWidth;
    for (const tile of Object.values(this.tileMap)) {
      tile.setVideoIconWidth(videoIconWidth);
    }
  }

  setLineWidth(lineWidth) {
    this.lineWidth = lineWidth;
    for (const tile of Object.values(this.tileMap)) {
      tile.setLineWidth(lineWidth);
    }
    this.selectLine.mesh.material.uniforms.lineWidth.value = lineWidth;
    this.selectLine.mesh.material.needsUpdate = true;
  }

  setLineOffset(lineOffset) {
    this.lineOffset = lineOffset;
    for (const tile of Object.values(this.tileMap)) {
      tile.setLineOffset(lineOffset);
    }
    this.selectLine.mesh.material.uniforms.lineOffset.value = lineOffset;
    this.selectLine.mesh.material.needsUpdate = true;
  }

  // 设置拾取图层颜色
  setPickLayerColor(pickLayerColor) {
    this.pickLayerColor = pickLayerColor;
    for (const tile of Object.values(this.tileMap)) {
      tile.setPickLayerColor(pickLayerColor);
    }
  }

  on(type, data) {
    if (type == MAP_EVENT.UPDATE_CENTER) {
      this.loadMesh();
    }
    if (type == MAP_EVENT.HANDLE_PICK_LEFT && data.layerId == this.id) {
      const pickColorNum = data.pickColor;
      for (const tile of Object.values(this.tileMap)) {
        const lineItem = tile.getLineByPickColor(pickColorNum);
        const nodeItem = tile.getNodeByPickColor(pickColorNum);
        const videoIconItem = tile.getVideoIconByPickColor(pickColorNum);
        if (lineItem) {
          this.handleEventListener(type, JSON.parse(JSON.stringify(lineItem)));
          break;
        } else if (nodeItem) {
          this.handleEventListener(type, JSON.parse(JSON.stringify(nodeItem)));
          break;
        } else if (videoIconItem) {
          this.handleEventListener(type, JSON.parse(JSON.stringify(videoIconItem)));
          break;
        }
      }
    }
  }

  onAdd(map) {
    super.onAdd(map);
    this.loadMesh();
  }

  handleLoadTile(tile) {
    if (this.loadingNum < 20) {
      this.loadingNum++;
      this.handleEventListener(MAP_EVENT.LAYER_LOADING, this.loadingNum > 0);
      tile.load(this).then((tile) => {
        tile.pickColorOffset = this.pickColorOffset;
        this.pickColorOffset += tile.pickColorNum;
        tile.update();

        this.loadingNum--;
        this.handleEventListener(MAP_EVENT.LAYER_LOADING, this.loadingNum > 0);
        if (this._noLoadTileList.length > 0) {
          const _tile = this._noLoadTileList.shift();
          this.handleLoadTile(_tile);
        }
      });
    } else {
      this._noLoadTileList.push(tile);
    }
  }

  async loadMesh() {
    this.updateTimeout = null;
    this.clearScene();
    const { row, col, size } = this.map.getTileRangeByZoom(BUILD_ZOOM);
    for (let i = row[0]; i <= row[1]; i++) {
      for (let j = col[0]; j <= col[1]; j++) {
        let key = `${i}_${j}`;
        let tile = this.tileMap[key];
        if (!tile) {
          tile = new NetworkTile({
            row: i,
            col: j,
            time: this.time,
            lineWidth: this.lineWidth,
            lineOffset: this.lineOffset,
            colors: this.colors,
            pickLayerColor: this.pickLayerColor,
            videoIconWidth: this.videoIconWidth,
            showNode: this.showNode,
            showVideoIcon: this.showVideoIcon,
          });
          this.tileMap[key] = tile;
          this.handleLoadTile(tile);
        }
        const [x, y] = this.map.WebMercatorToCanvasXY(tile.x, tile.y);
        tile.baseScene.position.set(x, y, 0);
        this.scene.add(tile.baseScene);

        tile.pickLayerScene.position.set(x, y, 0);
        this.pickLayerScene.add(tile.pickLayerScene);

        tile.pickMeshScene.position.set(x, y, 0);
        this.pickMeshScene.add(tile.pickMeshScene);
      }
    }

    if (this.selectLine.show) {
      const { line, tile, mesh } = this.selectLine;
      const [x, y] = this.map.WebMercatorToCanvasXY(tile.x, tile.y);
      mesh.position.set(x, y, 1.5);
      this.scene.add(mesh);
    } else if (this.selectLine.mesh) {
      this.scene.remove(this.selectLine.mesh);
    }
  }
}

export class NetworkTile {
  static noodMap = new THREE.TextureLoader().load(require("@/assets/image/point2.png"));
  static videoIconMap = new THREE.TextureLoader().load(window.VUE_APP_EXTERNAL_FILE_PATH + "/icon_traffic/camera-fill.svg");
  static lineMap = new THREE.TextureLoader().load(require("@/assets/image/up2.png"));

  _loadNum = 0;

  // 加载状态 1未加载 2加载成功 3加载失败 4加载中
  _loadStatus = 1;

  pickColorNum = 0;
  pickColorOffset = 0;

  get loadStatus() {
    return this._loadStatus;
  }
  get row() {
    return this._row;
  }
  get col() {
    return this._col;
  }
  get x() {
    return this._x;
  }
  get y() {
    return this._y;
  }
  get baseScene() {
    return this._baseScene;
  }
  get pickLayerScene() {
    return this._pickLayerScene;
  }
  get pickMeshScene() {
    return this._pickMeshScene;
  }
  get flowNum() {
    return this._flowNum;
  }

  constructor({ row, col, flowNum = 0, lineWidth = 10, lineOffset = 0, colors = ColorBar2DColors, pickLayerColor = 0xff0000, showNode = false, showVideoIcon = false, videoIconWidth = 10 }) {
    this._row = row;
    this._col = col;
    this._x = ((row + 0.5) * (EARTH_RADIUS * 2)) / Math.pow(2, BUILD_ZOOM) - EARTH_RADIUS;
    this._y = EARTH_RADIUS - ((col + 0.5) * (EARTH_RADIUS * 2)) / Math.pow(2, BUILD_ZOOM);
    this._lineOffset = lineOffset;
    this._lineWidth = lineWidth;
    this._colors = colors;
    this._pickLayerColor = pickLayerColor;
    this._showNode = showNode;
    this._showVideoIcon = showVideoIcon;
    this._nodeData = {};
    this._lineData = {};
    this._videoIconData = {};
    this._videoIconWidth = videoIconWidth;

    this._geometry = new THREE.BufferGeometry();
    this._baseMaterial = new NetworkMaterial({
      side: THREE.DoubleSide,
      color: 0xff0000,
      colorBar: ColorBar2DInstance.drowColorBar(this._colors),
      lineWidth: this._lineWidth,
      lineOffset: this._lineOffset,

      // map: NetworkTile.lineMap,
    });
    this._pickLayerMaterial = new NetworkMaterial({
      color: this._pickLayerColor,
      lineWidth: this._lineWidth,
      lineOffset: this._lineOffset,
    });
    this._pickMeshMaterial = new NetworkMaterial({
      usePickColor: true,
      lineWidth: this._lineWidth,
      lineOffset: this._lineOffset,
    });

    this._videoIconGeometry = new THREE.PlaneGeometry(100, 100);
    this._baseVideoIconMaterial = new THREE.MeshBasicMaterial({
      depthWrite: false,
      map: NetworkTile.videoIconMap,
      color: new THREE.Color(0xffb93d),
      transparent: true,
    });
    this._pickLayerVideoIconMaterial = new THREE.MeshBasicMaterial({ color: this._pickLayerColor });
    this._pickMeshVideoIconMaterial = new THREE.MeshBasicMaterial();

    this._nodeGeometry = new THREE.PlaneGeometry(100, 100);
    this._baseNodeMaterial = new THREE.MeshBasicMaterial({
      depthWrite: false,
      map: NetworkTile.noodMap,
      color: new THREE.Color(0xff0000),
      transparent: true,
    });
    this._pickLayerNodeMaterial = new THREE.MeshBasicMaterial({ color: this._pickLayerColor });
    this._pickMeshNodeMaterial = new THREE.MeshBasicMaterial();

    this._baseScene = new THREE.Group();
    this._pickLayerScene = new THREE.Group();
    this._pickMeshScene = new THREE.Group();

    this._flowNum = flowNum;
  }

  clear() {
    this._baseScene.remove(...this._baseScene.children);
    this._pickLayerScene.remove(...this._pickLayerScene.children);
    this._pickMeshScene.remove(...this._pickMeshScene.children);

    this._baseNodeMesh = null;
    this._pickLayerNodeMesh = null;
    this._pickMeshNodeMesh = null;
  }

  async load() {
    try {
      this._loadStatus = 4;
      let pickColorNum = 0;
      const { data } = await getTileNetwork({ x: this._row, y: this._col });
      if (data && data.length > 0) {
        this._lineData = {};
        this._nodeData = {};
        this._videoIconData = {};
        const nodeObj = {};
        for (const v of data) {
          v.pickColorNum = ++pickColorNum;
          v.type = "line";
          v.uuid = guid();
          v.id = v.linkId;
          v.normal = new THREE.Vector3(v.fromCoord.y - v.toCoord.y, v.toCoord.x - v.fromCoord.x, 0);
          this._lineData[v.pickColorNum] = v;
          if (v.realStats) {
            const videoIcon = { coord: v.realStats, id: v.linkId, pickColorNum: ++pickColorNum, type: "videoIcon", uuid: guid(), normal: v.normal.clone() };
            this._videoIconData[videoIcon.pickColorNum] = videoIcon;
          }
          const fromNode = { coord: v.fromCoord, id: v.fromNodeId, type: "node", uuid: guid(), normal: v.normal.clone() };
          const toNode = { coord: v.toCoord, id: v.toNodeId, type: "node", uuid: guid(), normal: v.normal.clone() };
          if (!nodeObj[fromNode.id]) {
            fromNode.pickColorNum = ++pickColorNum;
            this._nodeData[fromNode.pickColorNum] = fromNode;
            nodeObj[fromNode.id] = true;
          }
          if (!nodeObj[toNode.id]) {
            toNode.pickColorNum = ++pickColorNum;
            this._nodeData[toNode.pickColorNum] = toNode;
            nodeObj[toNode.id] = true;
          }
        }
      }
      this.pickColorNum = pickColorNum;
      this._loadStatus = 2;
    } catch (error) {
      console.log(error);
      this._loadStatus = 3;
    }
    return this;
  }

  update() {
    this._geometry = new NetworkGeometry(Array.from(Object.values(this._lineData)), this._flowNum, this.pickColorOffset);

    // this._baseMaterial.uniforms.flowMax.value = this._geometry.flowMax;
    // this._baseMaterial.uniforms.flowMin.value = this._geometry.flowMin;
    // this._baseMaterial.needsUpdate = true;
    // this._pickLayerMaterial.uniforms.flowMax.value = this._geometry.flowMax;
    // this._pickLayerMaterial.uniforms.flowMin.value = this._geometry.flowMin;
    // this._pickLayerMaterial.needsUpdate = true;
    // this._pickMeshMaterial.uniforms.flowMax.value = this._geometry.flowMax;
    // this._pickMeshMaterial.uniforms.flowMin.value = this._geometry.flowMin;
    // this._pickMeshMaterial.needsUpdate = true;

    this._baseMesh = new THREE.Mesh(this._geometry, this._baseMaterial);
    this._pickLayerMesh = new THREE.Mesh(this._geometry, this._pickLayerMaterial);
    this._pickBuildMesh = new THREE.Mesh(this._geometry, this._pickMeshMaterial);
    this._baseScene.add(this._baseMesh);
    this._pickLayerScene.add(this._pickLayerMesh);
    this._pickMeshScene.add(this._pickBuildMesh);

    const _scale2 = (this._videoIconWidth / 100) * 1.1;
    const videoIconList = Object.values(this._videoIconData);
    this._baseVideoIconMesh = new THREE.InstancedMesh(this._videoIconGeometry, this._baseVideoIconMaterial, videoIconList.length);
    this._pickLayerVideoIconMesh = new THREE.InstancedMesh(this._nodeGeometry, this._pickLayerVideoIconMaterial, videoIconList.length);
    this._pickMeshVideoIconMesh = new THREE.InstancedMesh(this._nodeGeometry, this._pickMeshVideoIconMaterial, videoIconList.length);

    for (let i = 0, l = videoIconList.length; i < l; i++) {
      const { coord, pickColorNum, normal } = videoIconList[i];
      const matrix = new THREE.Matrix4();
      const positionV3 = new THREE.Vector3(coord.x, coord.y, coord.z + i / l + 0.1).sub(normal.clone().setLength(this._lineOffset));
      const scaleV3 = new THREE.Vector3(_scale2, _scale2, 1);
      matrix.compose(positionV3, new THREE.Quaternion(), scaleV3);

      this._baseVideoIconMesh.setMatrixAt(i, matrix);
      this._pickLayerVideoIconMesh.setMatrixAt(i, matrix);
      this._pickMeshVideoIconMesh.setMatrixAt(i, matrix);
      this._pickMeshVideoIconMesh.setColorAt(i, new THREE.Color(pickColorNum + this.pickColorOffset));
    }
    this._baseVideoIconMesh.instanceMatrix.needsUpdate = true;
    if (this._showVideoIcon) {
      this._baseScene.add(this._baseVideoIconMesh);
      this._pickLayerScene.add(this._pickLayerVideoIconMesh);
      this._pickMeshScene.add(this._pickMeshVideoIconMesh);
    }

    const _scale = (this._lineWidth / 100) * 1.1;
    const nodeList = Object.values(this._nodeData);
    this._baseNodeMesh = new THREE.InstancedMesh(this._nodeGeometry, this._baseNodeMaterial, nodeList.length);
    this._pickLayerNodeMesh = new THREE.InstancedMesh(this._nodeGeometry, this._pickLayerNodeMaterial, nodeList.length);
    this._pickMeshNodeMesh = new THREE.InstancedMesh(this._nodeGeometry, this._pickMeshNodeMaterial, nodeList.length);

    for (let i = 0, l = nodeList.length; i < l; i++) {
      const { coord, pickColorNum, normal } = nodeList[i];
      const matrix = new THREE.Matrix4();
      const positionV3 = new THREE.Vector3(coord.x, coord.y, i / l + 1).sub(normal.clone().setLength(this._lineOffset));
      const scaleV3 = new THREE.Vector3(_scale, _scale, 1);
      matrix.compose(positionV3, new THREE.Quaternion(), scaleV3);

      this._baseNodeMesh.setMatrixAt(i, matrix);
      this._pickLayerNodeMesh.setMatrixAt(i, matrix);
      this._pickMeshNodeMesh.setMatrixAt(i, matrix);
      this._pickMeshNodeMesh.setColorAt(i, new THREE.Color(pickColorNum + this.pickColorOffset));
    }
    this._baseNodeMesh.instanceMatrix.needsUpdate = true;
    this._pickLayerNodeMesh.instanceMatrix.needsUpdate = true;
    this._pickMeshNodeMesh.instanceMatrix.needsUpdate = true;
    if (this._showNode) {
      this._baseScene.add(this._baseNodeMesh);
      this._pickLayerScene.add(this._pickLayerNodeMesh);
      this._pickMeshScene.add(this._pickMeshNodeMesh);
    }
  }

  getLineById(lineId) {
    for (const line of Object.values(this._lineData)) {
      if (line.id === lineId) {
        return line;
      }
    }
    return null;
  }

  getNodeById(nodeId) {
    for (const node of Object.values(this._nodeData)) {
      if (node.id === nodeId) {
        return node;
      }
    }
    return null;
  }

  getLineByPickColor(pickColor) {
    return this._lineData[pickColor - this.pickColorOffset];
  }

  getNodeByPickColor(pickColor) {
    return this._nodeData[pickColor - this.pickColorOffset];
  }

  getVideoIconByPickColor(pickColor) {
    return this._videoIconData[pickColor - this.pickColorOffset];
  }

  setColors(colors) {
    this._colors = colors || ColorBar2DColors;
    this._baseMaterial.uniforms.colorBar.value = ColorBar2DInstance.drowColorBar(this._colors);
    this._baseMaterial.needsUpdate = true;
  }

  setPickLayerColor(pickLayerColor) {
    this._pickLayerColor = pickLayerColor;
    this._pickLayerMaterial.uniforms.diffuse.value = pickLayerColor;
    this._pickLayerMaterial.needsUpdate = true;
  }

  setLineWidth(lineWidth) {
    this._lineWidth = lineWidth;
    this._baseMaterial.uniforms.lineWidth.value = this._lineWidth;
    this._baseMaterial.needsUpdate = true;
    this._pickLayerMaterial.uniforms.lineWidth.value = this._lineWidth;
    this._pickLayerMaterial.needsUpdate = true;
    this._pickMeshMaterial.uniforms.lineWidth.value = this._lineWidth;
    this._pickMeshMaterial.needsUpdate = true;

    if (this.loadStatus != 2) return;
    const _scale = (this._lineWidth / 100) * 1.1;

    const nodeList = Object.values(this._nodeData);
    for (let i = 0, l = nodeList.length; i < l; i++) {
      const { coord, pickColorNum, normal } = nodeList[i];
      const matrix = new THREE.Matrix4();
      const positionV3 = new THREE.Vector3(coord.x, coord.y, i / l + 1).sub(normal.clone().setLength(this._lineOffset));
      const scaleV3 = new THREE.Vector3(_scale, _scale, 1);
      matrix.compose(positionV3, new THREE.Quaternion(), scaleV3);

      if (this._baseNodeMesh) this._baseNodeMesh.setMatrixAt(i, matrix);
      if (this._pickLayerNodeMesh) this._pickLayerNodeMesh.setMatrixAt(i, matrix);
      if (this._pickMeshNodeMesh) this._pickMeshNodeMesh.setMatrixAt(i, matrix);
    }
    if (this._baseNodeMesh) this._baseNodeMesh.instanceMatrix.needsUpdate = true;
    if (this._pickLayerNodeMesh) this._pickLayerNodeMesh.instanceMatrix.needsUpdate = true;
    if (this._pickMeshNodeMesh) this._pickMeshNodeMesh.instanceMatrix.needsUpdate = true;
  }

  setVideoIconWidth(videoIconWidth) {
    this._videoIconWidth = videoIconWidth;

    if (this.loadStatus != 2) return;
    const _scale2 = (this._videoIconWidth / 100) * 1.1;

    const videoIconList = Object.values(this._videoIconData);
    for (let i = 0, l = videoIconList.length; i < l; i++) {
      const { coord, pickColorNum, normal } = videoIconList[i];
      const matrix = new THREE.Matrix4();
      const positionV3 = new THREE.Vector3(coord.x, coord.y, i / l + 1).sub(normal.clone().setLength(this._lineOffset));
      const scaleV3 = new THREE.Vector3(_scale2, _scale2, 1);

      matrix.compose(positionV3, new THREE.Quaternion(), scaleV3);

      if (this._baseVideoIconMesh) this._baseVideoIconMesh.setMatrixAt(i, matrix);
      if (this._pickLayerVideoIconMesh) this._pickLayerVideoIconMesh.setMatrixAt(i, matrix);
      if (this._pickMeshVideoIconMesh) this._pickMeshVideoIconMesh.setMatrixAt(i, matrix);
    }
    if (this._baseVideoIconMesh) this._baseVideoIconMesh.instanceMatrix.needsUpdate = true;
    if (this._pickLayerVideoIconMesh) this._pickLayerVideoIconMesh.instanceMatrix.needsUpdate = true;
    if (this._pickMeshVideoIconMesh) this._pickMeshVideoIconMesh.instanceMatrix.needsUpdate = true;
  }

  setLineOffset(lineOffset) {
    this._lineOffset = lineOffset;
    this._baseMaterial.uniforms.lineOffset.value = this._lineOffset;
    this._baseMaterial.needsUpdate = true;
    this._pickLayerMaterial.uniforms.lineOffset.value = this._lineOffset;
    this._pickLayerMaterial.needsUpdate = true;
    this._pickMeshMaterial.uniforms.lineOffset.value = this._lineOffset;
    this._pickMeshMaterial.needsUpdate = true;

    if (this.loadStatus != 2) return;

    if (this.loadStatus != 2) return;
    const _scale2 = (this._videoIconWidth / 100) * 1.1;

    const videoIconList = Object.values(this._videoIconData);
    for (let i = 0, l = videoIconList.length; i < l; i++) {
      const { coord, pickColorNum, normal } = videoIconList[i];
      const matrix = new THREE.Matrix4();
      const positionV3 = new THREE.Vector3(coord.x, coord.y, i / l + 1).sub(normal.clone().setLength(this._lineOffset));
      const scaleV3 = new THREE.Vector3(_scale2, _scale2, 1);

      matrix.compose(positionV3, new THREE.Quaternion(), scaleV3);

      if (this._baseVideoIconMesh) this._baseVideoIconMesh.setMatrixAt(i, matrix);
      if (this._pickLayerVideoIconMesh) this._pickLayerVideoIconMesh.setMatrixAt(i, matrix);
      if (this._pickMeshVideoIconMesh) this._pickMeshVideoIconMesh.setMatrixAt(i, matrix);
    }
    if (this._baseVideoIconMesh) this._baseVideoIconMesh.instanceMatrix.needsUpdate = true;
    if (this._pickLayerVideoIconMesh) this._pickLayerVideoIconMesh.instanceMatrix.needsUpdate = true;
    if (this._pickMeshVideoIconMesh) this._pickMeshVideoIconMesh.instanceMatrix.needsUpdate = true;

    const _scale = (this._lineWidth / 100) * 1.1;
    const nodeList = Object.values(this._nodeData);
    for (let i = 0, l = nodeList.length; i < l; i++) {
      const { coord, pickColorNum, normal } = nodeList[i];
      const matrix = new THREE.Matrix4();
      const positionV3 = new THREE.Vector3(coord.x, coord.y, i / l + 1).sub(normal.clone().setLength(this._lineOffset));
      const scaleV3 = new THREE.Vector3(_scale, _scale, 1);
      matrix.compose(positionV3, new THREE.Quaternion(), scaleV3);

      if (this._baseNodeMesh) this._baseNodeMesh.setMatrixAt(i, matrix);
      if (this._pickLayerNodeMesh) this._pickLayerNodeMesh.setMatrixAt(i, matrix);
      if (this._pickMeshNodeMesh) this._pickMeshNodeMesh.setMatrixAt(i, matrix);
    }
    if (this._baseNodeMesh) this._baseNodeMesh.instanceMatrix.needsUpdate = true;
    if (this._pickLayerNodeMesh) this._pickLayerNodeMesh.instanceMatrix.needsUpdate = true;
    if (this._pickMeshNodeMesh) this._pickMeshNodeMesh.instanceMatrix.needsUpdate = true;
  }

  setTime(time) {
    this._flowNum = Math.floor(time / 3600);
    if (this.loadStatus != 2) return;
    if (this._geometry.isNetworkGeometry) {
      this._geometry.setFlowNum(this._flowNum);
    }
  }

  setShowNode(showNode) {
    this._showNode = showNode;
    if (showNode) {
      if (this._baseNodeMesh) this._baseScene.add(this._baseNodeMesh);
      if (this._pickLayerNodeMesh) this._pickLayerScene.add(this._pickLayerNodeMesh);
      if (this._pickMeshNodeMesh) this._pickMeshScene.add(this._pickMeshNodeMesh);
    } else {
      if (this._baseNodeMesh) this._baseNodeMesh.removeFromParent();
      if (this._pickLayerNodeMesh) this._pickLayerNodeMesh.removeFromParent();
      if (this._pickMeshNodeMesh) this._pickMeshNodeMesh.removeFromParent();
    }
  }

  setShowVideoIcon(showVideoIcon) {
    this._showVideoIcon = showVideoIcon;

    if (showVideoIcon) {
      if (this._baseVideoIconMesh) this._baseScene.add(this._baseVideoIconMesh);
      if (this._pickLayerVideoIconMesh) this._pickLayerScene.add(this._pickLayerVideoIconMesh);
      if (this._pickMeshVideoIconMesh) this._pickMeshScene.add(this._pickMeshVideoIconMesh);
    } else {
      if (this._baseVideoIconMesh) this._baseVideoIconMesh.removeFromParent();
      if (this._pickLayerVideoIconMesh) this._pickLayerVideoIconMesh.removeFromParent();
      if (this._pickMeshVideoIconMesh) this._pickMeshVideoIconMesh.removeFromParent();
    }
  }

  toJSON() {
    const data = {
      row: this._row,
      col: this._col,
      flowNum: this._flowNum,
      lineWidth: this._lineWidth,
      lineOffset: this._lineOffset,
      colors: this._colors,
      pickLayerColor: this._pickLayerColor,
      showNode: this._showNode,
      showVideoIcon: this._showVideoIcon,
      videoIconWidth: this._videoIconWidth,
      loadStatus: this._loadStatus,
    };
    if (this._loadStatus == 2) {
      data.lineData = JSON.parse(JSON.stringify(this._lineData));
      data.nodeData = JSON.parse(JSON.stringify(this._nodeData));
      data.videoIconData = JSON.parse(JSON.stringify(this._videoIconData));
      data.geometry = this._geometry.toJSON();
    }
    return data;
  }

  static fromJSON(json) {
    const tile = new NetworkTile({
      row: json.row,
      col: json.col,
      flowNum: json.flowNum,
      lineWidth: json.lineWidth,
      lineOffset: json.lineOffset,
      colors: json.colors,
      pickLayerColor: json.pickLayerColor,
      showNode: json.showNode,
      showVideoIcon: json.showVideoIcon,
      videoIconWidth: json.videoIconWidth,
    });
    if (json.loadStatus == 2) {
      tile._loadStatus = 2;
      tile._lineData = json.lineData;
      tile._nodeData = json.nodeData;
      tile._videoIconData = json.videoIconData;
      tile._geometry = NetworkGeometry.fromJSON(json.geometry);
    }
    return tile;
  }
}

export class NetworkGeometry extends THREE.BufferGeometry {
  static nullFlows = new THREE.Float32BufferAttribute([], 2);

  constructor(lineList, flowNum, pickColorOffset) {
    super();
    this.type = "NetworkGeometry";
    this.isNetworkGeometry = true;

    const posLen = lineList.length * 4;

    const pickColors = []; // new Array(posLen * 3);
    const positions = []; // new Array(posLen * 3);
    const uvs = []; // new Array(posLen * 3);
    const indices = []; // new Array(lineList.length * 6);
    const lineNormals = [];
    const lineLengths = [];

    const flowsMap = new Map();

    let flowMax = Number.MIN_SAFE_INTEGER;
    let flowMin = Number.MAX_SAFE_INTEGER;

    for (let i = 0, l = lineList.length; i < l; i++) {
      const { pickColorNum, flows, fromCoord, toCoord } = lineList[i];
      const pickColor = new THREE.Color(pickColorNum + pickColorOffset).toArray();
      for (let j = 0; j < flows.length; j++) {
        if (!flowsMap.has(String(j))) {
          const list = new THREE.Float32BufferAttribute(new Array(posLen * 4).fill(0), 1);
          flowsMap.set(String(j), list);
        }
        const flow = flows[j];
        if (flowMax < flow) flowMax = flow;
        if (flowMin > flow) flowMin = flow;
        const list = flowsMap.get(String(j));
        list.setX(i * 4, flow);
        list.setX(i * 4 + 1, flow);
        list.setX(i * 4 + 2, flow);
        list.setX(i * 4 + 3, flow);
      }
      const lineDirection = new THREE.Vector2(toCoord.x - fromCoord.x, toCoord.y - fromCoord.y);
      if (toCoord.x == fromCoord.x && toCoord.y == fromCoord.y && toCoord.z != fromCoord.z) {
        lineDirection.set(0, 1);
      }
      const lineLength = lineDirection.length();
      lineDirection.normalize();
      const normal = new THREE.Vector2(-lineDirection.y, lineDirection.x);

      // 起点
      lineLengths.push(lineLength);
      lineLengths.push(lineLength);
      lineNormals.push(normal.x, normal.y, 1);
      lineNormals.push(normal.x, normal.y, -1);
      positions.push(fromCoord.x, fromCoord.y, fromCoord.z);
      positions.push(fromCoord.x, fromCoord.y, fromCoord.z);
      pickColors.push(...pickColor);
      pickColors.push(...pickColor);
      uvs.push(0, 0);
      uvs.push(0, 1);
      // 终点
      lineLengths.push(lineLength);
      lineLengths.push(lineLength);
      lineNormals.push(normal.x, normal.y, -1);
      lineNormals.push(normal.x, normal.y, 1);
      positions.push(toCoord.x, toCoord.y, toCoord.z);
      positions.push(toCoord.x, toCoord.y, toCoord.z);
      pickColors.push(...pickColor);
      pickColors.push(...pickColor);
      uvs.push(1, 1);
      uvs.push(1, 0);
      // 三角形顶点
      indices.push(i * 4, i * 4 + 1, i * 4 + 2);
      indices.push(i * 4, i * 4 + 2, i * 4 + 3);
    }

    this.setIndex(indices);
    this.setAttribute("lineLength", new THREE.Float32BufferAttribute(lineLengths, 1));
    this.setAttribute("position", new THREE.Float32BufferAttribute(positions, 3));
    this.setAttribute("pickColor", new THREE.Float32BufferAttribute(pickColors, 3));
    this.setAttribute("lineNormal", new THREE.Float32BufferAttribute(lineNormals, 3));
    this.setAttribute("uv", new THREE.Float32BufferAttribute(uvs, 2));
    this.setAttribute("flow", flowsMap.get(String(flowNum)) || NetworkGeometry.nullFlows);
    this.computeVertexNormals();

    this.flowsMap = flowsMap;
    this.flowMax = flowMax;
    this.flowMin = flowMin;
    this.flowNum = String(flowNum);
  }

  setFlowNum(flowNum) {
    if (this.flowNum != String(flowNum)) {
      this.flowNum = String(flowNum);
      this.setAttribute("flow", this.flowsMap.get(String(this.flowNum)) || NetworkGeometry.nullFlows);
    }
  }

  toJSON() {
    const data = super.toJSON();
    data.data.flowMax = this.flowMax;
    data.data.flowMin = this.flowMin;
    data.data.flowsMap = {};
    for (const key of this.flowsMap.keys()) {
      const arr = this.flowsMap.get(key);
      data.data.flowsMap[key] = arr.toJSON();
    }
    return data;
  }

  static fromJSON(json) {
    const { flowMax, flowMin, flowsMap, attributes, index } = json.data;
    const geometry = new NetworkGeometry([], 0);
    geometry.flowMax = flowMax;
    geometry.flowMin = flowMin;
    for (const key of Object.keys(flowsMap)) {
      const { itemSize, type, array, normalized } = flowsMap[key];
      const arr = new THREE.Float32BufferAttribute(array, itemSize);
      geometry.flowsMap.set(String(key), arr);
    }
    for (const key of Object.keys(attributes)) {
      const { itemSize, type, array, normalized } = attributes[key];
      const arr = new THREE.Float32BufferAttribute(array, itemSize);
      geometry.setAttribute(key, arr);
    }
    geometry.setIndex(index.array);
    geometry.computeVertexNormals();
    return geometry;
  }

  dispose() {
    super.dispose();
  }
}

export class NetworkMaterial extends THREE.Material {
  constructor(argu) {
    super();
    const { color = 0xffffff, opacity = 1, usePickColor = false, lineWidth = 10, lineOffset = 0, map = null, colorBar, maxColorValue, ...params } = argu || {};
    this.defines = {
      USE_PICK_COLOR: !!usePickColor,
      USE_MAP: !!map,
      USE_COLOR_BAR: !!colorBar,
    };
    this.uniforms = {
      diffuse: {
        value: new THREE.Color(color),
      },
      opacity: {
        value: opacity,
      },
      map: {
        value: map,
      },
      uvTransform: {
        value: new THREE.Matrix3(),
      },
      lineWidth: {
        value: lineWidth,
      },
      lineOffset: {
        value: lineOffset,
      },
      colorBar: {
        value: colorBar,
        properties: {
          map: {},
          max: {},
          min: {},
          range: {},
        },
      },
      flowMax: {
        value: 1,
      },
      flowMin: {
        value: 0,
      },
    };
    this.vertexShader = `
      #include <common>
      #include <logdepthbuf_pars_vertex>
      
      attribute vec3 pickColor;
      attribute vec3 lineNormal;
      attribute float lineLength;
      attribute float flow;
      // attribute vec2 uv;
      
      varying vec3 vColor;
      varying vec3 vPickColor;
      varying vec2 vUv;
      varying float vFlow;
      varying float vLineLength;

      uniform float lineWidth;
      uniform float lineOffset;
      uniform mat3 uvTransform;
      uniform float flowMax;
      uniform float flowMin;


      void main() {

        vFlow = flow;
        vPickColor = pickColor;
        vLineLength = lineLength;

        vec3 transformed = vec3(1.0);

        #ifdef USE_MAP
          vUv = ( uvTransform * vec3( uv, 1 ) ).xy;
        #endif
        transformed = vec3(position.xy + lineNormal.xy * lineNormal.z * lineWidth / 2.0 - lineNormal.xy * lineOffset, position.z + flow / (flowMax - flowMin));

        gl_Position = projectionMatrix * modelViewMatrix * vec4( transformed, 1.0 );

        #include <logdepthbuf_vertex>

      }
    `;
    this.fragmentShader = `
      #include <common>
      #include <logdepthbuf_pars_fragment>

      struct ColorBar {
        sampler2D map;
        float max;
        float min;
        float range;
      };

      uniform float lineWidth;
      uniform vec3 diffuse;
      uniform float opacity;
      uniform sampler2D map;
      uniform ColorBar colorBar;
      
      varying vec3 vColor;
      varying vec3 vPickColor;
      varying vec2 vUv;
      varying float vFlow;
      varying float vLineLength;


      void main() {
        vec4 diffuseColor = vec4( diffuse, opacity );
        
        #include <logdepthbuf_fragment>
        
        #ifdef USE_COLOR_BAR
          vec4 barDiffuseColor = texture2D(colorBar.map, vec2(vFlow / colorBar.range , 0.5));
          diffuseColor = barDiffuseColor;
        #endif

        #ifdef USE_MAP
          vec2 uv = vUv;
          uv.x = mod(vUv.x * vLineLength, lineWidth) / lineWidth;
          vec4 sampledDiffuseColor = texture2D(map, uv);
          sampledDiffuseColor.rgb *= sampledDiffuseColor.a;
          diffuseColor.rgb += sampledDiffuseColor.rgb;
        #endif

        #ifdef USE_PICK_COLOR
          diffuseColor = vec4( vPickColor, 1.0);
        #endif

        gl_FragColor = diffuseColor;

      }
    `;
    this.setValues(params);
  }
}

export class ColorBar2D {
  static width = 1024;
  static height = 16;
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

  // 渐变颜色条图
  drowColorBar(colors = ColorBar2DColors) {
    try {
      //颜色条的颜色分布
      const values = Object.keys(colors)
        .map((v) => Number(v))
        .sort();
      const maxValue = values[values.length - 1];
      const minValue = values[0];

      // 创建线性渐变色
      const linearGradient = this.context2D.createLinearGradient(0, 0, ColorBar2D.width, 0);
      for (const v of values) {
        const key = (v - minValue) / maxValue - minValue;
        linearGradient.addColorStop(key, colors[v]);
      }

      // 绘制渐变色条
      this.context2D.fillStyle = linearGradient;
      this.context2D.fillRect(0, 0, ColorBar2D.width, ColorBar2D.height);

      const url = this.canvas2D.toDataURL("image/png");
      this.context2D.clearRect(0, 0, ColorBar2D.width, ColorBar2D.height);

      return {
        url: url,
        map: new THREE.TextureLoader().load(url),
        max: maxValue,
        min: minValue,
        range: maxValue - minValue,
      };
    } catch (error) {
      return null;
    }
  }
}

const ColorBar2DInstance = new ColorBar2D();

const ColorBar2DColors = { 0: "#313695", 0.4: "#74add1", 0.6: "#e0f3f8", 0.75: "#ffffbf", 0.85: "#fdae61", 0.95: "#f46d43", 1: "#a50026" };
