import { Layer, MAP_EVENT, OutlineLayer, SCENE_MAP } from "@/mymap";
import * as THREE from "three";

export class LinkFlowLayer extends Layer {
  color = 0x0000ff;
  height = 100;
  constructor(opt) {
    super(opt);
    this.color = opt.color || this.color;
    this.height = opt.height || this.height;

    this.material = new THREE.LineBasicMaterial({
      color: this.color,
    });
  }

  setHeight(height) {
    this.height = height;
    for (const mesh of this.meshList) {
      mesh.scale.set(1, 1, this.height / 100);
    }
  }

  setColor(color) {
    this.color = color;
    this.material.setValues({ color: color });
    this.material.needsUpdate = true;
  }

  on(type, data) {
    if (type == MAP_EVENT.UPDATE_CENTER) {
      for (const mesh of this.scene.children) {
        const [x, y] = this.map.WebMercatorToCanvasXY(...mesh.userData.center);
        mesh.position.set(x, y, mesh.position.z);
      }
    }
  }

  onAdd(map) {
    super.onAdd(map);
    this.update();
  }

  render() {
    super.render();
  }

  setData(data) {
    this.data = data;
    this.update();
  }
  clearScene() {
    super.clearScene();
    if (this.meshList && this.meshList.length) {
      this.meshList.forEach((v) => v.geometry.dispose());
      this.meshList = [];
    }
  }

  update() {
    this.clearScene();
    if (!this.map) return;
    if (!this.data) return;
    console.time("update");

    const { link, legs } = this.data;

    // const geoList = new Array(legs.length).fill(null);
    const { center, fromCoord, toCoord } = link;
    const linkCenter = [center.x, center.y];
    // const fV3 = new THREE.Vector3(fromCoord.x, fromCoord.y, 0);
    // const tV3 = new THREE.Vector3(toCoord.x, toCoord.y, 0);
    // const cV3 = new THREE.Vector3(center.x, center.y, 0);
    // const linkDirection = fV3.clone().sub(tV3).normalize();
    // link的法向量
    // const linkNormal = new THREE.Vector3(linkDirection.y, linkDirection.x, 0);
    const meshList = [];
    for (let i = 0, l = legs.length; i < l; i++) {
      const { offset, coords } = legs[i];

      const points = coords.map((v) => new THREE.Vector3(v[0], v[1], v[2] / 60));
      const geometry = new THREE.BufferGeometry().setFromPoints(points);
      const mesh = new THREE.Line(geometry, this.material);

      // const legCenterV3 = cV3.clone().add(linkNormal.clone().multiplyScalar(offset));
      // const legCenter = [legCenterV3.x, legCenterV3.y];
      const [x, y] = this.map.WebMercatorToCanvasXY(...linkCenter);
      mesh.position.set(x, y, 0);
      mesh.scale.set(1, 1, this.height / 100);
      mesh.userData.center = linkCenter;

      mesh.layers.enable(SCENE_MAP.BLOOM_SCENE);
      meshList.push(mesh);
      this.scene.add(mesh);
    }
    this.meshList = meshList;
    console.timeEnd("update");
  }
}
