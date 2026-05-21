import * as THREE from "three";
import { Layer } from "../index";

const Loader = new THREE.TextureLoader();


export class WeatherLayer extends Layer {
  constructor(opt) {
    super(opt);
    this.styleMap = opt.styleMap || MAP_LAYER_STYLE;
    this.setStyleMap(this.styleMap);
  }

}