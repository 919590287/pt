import * as THREE from "three";
import { Layer, MAP_EVENT } from "@/mymap/index.js";
import { NetworkMaterial } from "./NetworkLayer"


export class SelectLineLayer extends Layer {
  color = 0xff0000;
  lineWidth = 10;

  constructor(opt) {
    super(opt);
    this.color = opt.color || this.color;
    this.lineWidth = opt.lineWidth || this.lineWidth;

    this.material = new NetworkMaterial({
      color: 0xffa500,
      lineWidth: this.lineWidth,
    });
    this.geometry = new THREE.BufferGeometry();
    this.mesh = new THREE.Mesh(this.geometry, this.material);
    this.mesh.userData.center = [0, 0]
    this.scene.add(this.mesh);
  }

  onAdd(map) {
    super.onAdd(map);
    this.update()
  }

  on(type, data) {
    if (type == MAP_EVENT.UPDATE_CENTER) {
      let [x, y] = this.map.WebMercatorToCanvasXY(this.mesh.userData.center[0], this.mesh.userData.center[1]);
      this.mesh.position.set(x, y, 0);
    }
    // if (type == MAP_EVENT.UPDATE_CAMERA_HEIGHT) {
    //   this.setSize(this.map.cameraHeight / 10000);
    // }
  }

  setLineOffset(lineOffset) {
    this.lineOffset = lineOffset;
    this.material.uniforms.lineOffset.value = this.lineOffset;
    this.material.needsUpdate = true;
  }

  setLineWidth(lineWidth) {
    this.lineWidth = lineWidth;
    this.material.uniforms.lineWidth.value = this.lineWidth;
    this.material.needsUpdate = true;
  }

  setData(data) {
    const { fromCoord, toCoord, center } = data
    this.data = {
      center: center,
      fromCoord: { x: fromCoord.x - center.x, y: fromCoord.y - center.y },
      toCoord: { x: toCoord.x - center.x, y: toCoord.y - center.y }
    };
    this.update();
  }

  update() {
    if (!this.map) return;
    if (!this.data) return;

    const { fromCoord, toCoord, center } = this.data

    const lineDirection = new THREE.Vector2(toCoord.x - fromCoord.x, toCoord.y - fromCoord.y);
    const lineLength = lineDirection.length();
    lineDirection.normalize();
    const normal = new THREE.Vector2(-lineDirection.y, lineDirection.x);

    const positions = [];
    const uvs = [];
    const indices = [];
    const lineNormals = [];
    const lineLengths = [];

    // 起点
    lineLengths.push(lineLength);
    lineLengths.push(lineLength);
    lineNormals.push(normal.x, normal.y, 1);
    lineNormals.push(normal.x, normal.y, -1);
    positions.push(fromCoord.x, fromCoord.y, 0);
    positions.push(fromCoord.x, fromCoord.y, 0);
    uvs.push(0, 0);
    uvs.push(0, 1);
    // 终点
    lineLengths.push(lineLength);
    lineLengths.push(lineLength);
    lineNormals.push(normal.x, normal.y, -1);
    lineNormals.push(normal.x, normal.y, 1);
    positions.push(toCoord.x, toCoord.y, 0);
    positions.push(toCoord.x, toCoord.y, 0);
    uvs.push(1, 1);
    uvs.push(1, 0);
    // 三角形顶点
    indices.push(0, 1, 2);
    indices.push(0, 2, 3);


    this.geometry.setIndex(indices);
    this.geometry.setAttribute("lineLength", new THREE.Float32BufferAttribute(lineLengths, 1));
    this.geometry.setAttribute("position", new THREE.Float32BufferAttribute(positions, 3));
    this.geometry.setAttribute("lineNormal", new THREE.Float32BufferAttribute(lineNormals, 3));
    this.geometry.setAttribute("uv", new THREE.Float32BufferAttribute(uvs, 2));
    this.geometry.computeVertexNormals();
    this.geometry.needsUpdate = true;

    this.mesh.userData.center = [center.x, center.y];

    let [x, y] = this.map.WebMercatorToCanvasXY(this.mesh.userData.center[0], this.mesh.userData.center[1]);
    this.mesh.position.set(x, y, 0);
  }

}