import { MAP_EVENT, Layer } from "../index";
import * as THREE from "three";

import { OutlinePass } from "three/examples/jsm/postprocessing/OutlinePass";

export class OutlineLayer extends Layer {
  passParams = {
    visibleEdgeColor: new THREE.Color(0x00ff00), // 呼吸显示的颜色
    hiddenEdgeColor: new THREE.Color(0x000000), // 呼吸消失的颜色
    edgeGlow: 1, // 光晕[0,1]
    usePatternTexture: false, // 是否使用父级的材质
    edgeThickness: 1.0, // 边框宽度
    edgeStrength: 10.0, // 边框的亮度
    downSampleRatio: 1, // 边框弯曲度
    pulsePeriod: 5, // 呼吸闪烁的速度
    clear: true,
  };

  constructor(opt) {
    super(opt);
    for (const key in this.passParams) {
      if (opt[key]) {
        this.passParams[key] = opt[key];
      }
    }
  }

  onAdd(map) {
    super.onAdd(map);
    const { camera, composer, scene, rootDoc } = map;

    this.outlinePass = new OutlinePass(new THREE.Vector2(rootDoc.clientWidth, rootDoc.clientHeight), scene, camera);

    for (const key in this.passParams) {
      this.outlinePass[key] = this.passParams[key];
    }

    composer.addPass(this.outlinePass);
  }
}
