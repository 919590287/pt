import * as THREE from "three";
import { Layer, MAP_EVENT } from "@/mymap/index.js";

import * as Bean from "@/utils/Bean";

const STOP_SIZE = 80;

export class SelectStopLayer extends Layer {
  name = "SelectStopLayer";
  size = 0.3;
  scale = 6;
  offset = 0;
  data = [];
  center = [0, 0];
  color = new THREE.Color(0xffa500);
  texture = new THREE.TextureLoader().load(require("@/assets/image/point1.png"));
  normal = new THREE.Vector3();

  constructor(opt) {
    super(opt);

    this.scale = opt.scale || this.scale;
    this.offset = opt.offset || this.offset;
    this.color = new THREE.Color(opt.color || this.color);

    this.geometry = new THREE.PlaneGeometry(STOP_SIZE, STOP_SIZE);
    this.material = new THREE.MeshBasicMaterial({
      transparent: true,
      map: this.texture,
      color: this.color
    });

    this.mesh = new THREE.Mesh(this.geometry, this.material);
  }

  onAdd(map) {
    super.onAdd(map);
    this.setSize(this.map.cameraHeight / 10000);
    this.update();
  }

  on(type, data) {
    if (type == MAP_EVENT.UPDATE_CENTER) {
      let [x, y] = this.map.WebMercatorToCanvasXY(this.data.x, this.data.y);
      this.mesh.position.set(x, y, 0).sub(this.normal.clone().setLength(this.offset));
    }
    if (type == MAP_EVENT.UPDATE_CAMERA_HEIGHT) {
      this.setSize(this.map.cameraHeight / 10000);
    }
  }

  setColor(color) {
    this.color = new THREE.Color(color);
    if (this.mesh) {
      for (let i = 0; i < this.mesh.count; i++) {
        this.mesh.setColorAt(i, this.color);
        this.mesh.instanceColor.needsUpdate = true;
      }
    }
  }

  setSize(size) {
    this.size = size;
    const _scale = 6 * size;
    this.mesh.scale.set(_scale, _scale, 1)
  }
  setOffset(offset) {
    this.offset = offset;
    this.update();
  }

  setData(data) {
    try {
      this.data = data;
      this.normal.set(data.normal.x, data.normal.y, data.normal.z);
      this.update();
    } catch (error) {
      this.data = null;
      this.normal.set(0, 0, 0);
      this.update();
    }
  }

  clearScene() {
    super.clearScene();
    this.geometry.dispose();
    this.texture.dispose();
  }

  update() {
    this.clearScene();
    if (!this.map) return;
    if (!this.data) return;
    let [x, y] = this.map.WebMercatorToCanvasXY(this.data.x, this.data.y);
    this.mesh.position.set(x, y, 0).sub(this.normal.clone().setLength(this.offset));
    this.scene.add(this.mesh);
  }
}
