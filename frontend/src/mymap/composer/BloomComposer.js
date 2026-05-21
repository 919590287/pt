import * as THREE from "three";
import { RenderPass } from "three/examples/jsm/postprocessing/RenderPass";
import { ShaderPass } from "three/examples/jsm/postprocessing/ShaderPass";
import { UnrealBloomPass } from "three/examples/jsm/postprocessing/UnrealBloomPass";
import { EffectComposer } from "three/examples/jsm/postprocessing/EffectComposer";
import { GammaCorrectionShader } from "three/examples/jsm/shaders/GammaCorrectionShader.js";

const BLOOM_PARAMS = {
  layerNum: 1,
  strength: 1,
  radius: 0,
  threshold: 0,
};

export const SCENE_MAP = {
  ENTIRE_SCENE: 0, // 全景图层
  BLOOM_SCENE: 1, // 泛光图层
};

export class BloomComposer {
  constructor(renderer, scene, camera, bloomParams) {
    const _bloomParams = Object.assign({}, BLOOM_PARAMS, bloomParams);
    this.renderer = renderer;
    this.scene = scene;
    this.camera = camera;
    this.materials = new Map();
    this.bloomLayer = new THREE.Layers();
    this.bloomLayer.set(_bloomParams.layerNum);

    this.background = new THREE.Color(0);

    this.blackMeshMaterial = new THREE.MeshBasicMaterial({
      color: this.background,
    });
    this.blackLineMaterial = new THREE.LineBasicMaterial({
      color: this.background,
    });
    this.blackPointsMaterial = new THREE.PointsMaterial({
      color: this.background,
    });
    this.blackSpriteMaterial = new THREE.SpriteMaterial({
      color: this.background,
    });

    this.renderPass = new RenderPass(scene, camera);
    this.bloomPass = new UnrealBloomPass(new THREE.Vector2(0, 0), _bloomParams.strength, _bloomParams.radius, _bloomParams.threshold);

    this.composer = new EffectComposer(renderer);
    this.composer.renderToScreen = false;
    this.composer.addPass(this.renderPass);
    this.composer.addPass(this.bloomPass);

    this.pass = new ShaderPass(
      new THREE.ShaderMaterial({
        uniforms: {
          baseTexture: { value: null },
          bloomTexture: { value: this.composer.renderTarget2.texture },
        },
        vertexShader: `
          varying vec2 vUv;
          void main() {
            vUv = uv;
            gl_Position = projectionMatrix * modelViewMatrix * vec4( position, 1.0 );
          }
        `,
        fragmentShader: `
          uniform sampler2D baseTexture;
          uniform sampler2D bloomTexture;
          varying vec2 vUv;
          void main() {
            vec4 bloomColor = texture2D( bloomTexture, vUv );
            vec4 baseColor = texture2D( baseTexture, vUv );
            float length = length(bloomColor.rgb) / 1.42;
            length = length * length * 0.7;
            bloomColor = vec4(length) * bloomColor;
            baseColor = vec4(1.0 - length) * baseColor;
            gl_FragColor = bloomColor + baseColor;
          }
        `,
        defines: {},
      }),
      "baseTexture"
    );
    this.pass.needsSwap = true;
  }

  setSize(width, height) {
    this.composer.setSize(width, height);
  }

  render() {
    const background = this.scene.background;
    const fog = this.scene.fog;
    this.scene.background = this.background;
    this.scene.fog = null;
    this.scene.traverse((obj) => {
      if (this.bloomLayer.test(obj.layers) === false) {
        this.materials.set(obj.uuid, obj.material);
        if (obj.isLine) {
          obj.material = this.blackLineMaterial;
        } else if (obj.isPoints) {
          obj.material = this.blackPointsMaterial;
        } else if (obj.isSprite) {
          obj.material = this.blackSpriteMaterial;
        } else if (obj.isMesh) {
          obj.material = this.blackMeshMaterial;
        }
      }
    });

    this.composer.render();

    this.scene.traverse((obj) => {
      const material = this.materials.get(obj.uuid);
      if (material) {
        obj.material = material;
        this.materials.delete(obj.uuid);
      }
    });

    this.scene.background = background;
    this.scene.fog = fog;
  }

  dispose() {
    this.blackLineMaterial.dispose();
    this.blackMeshMaterial.dispose();
    this.blackPointsMaterial.dispose();
    this.blackSpriteMaterial.dispose();
    this.composer.dispose();
    this.renderPass.dispose();
    this.bloomPass.dispose();
    this.pass.dispose();
  }
}
