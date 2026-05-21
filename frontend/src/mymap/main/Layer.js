import * as THREE from "three";
import { EventListener } from "./EventListener";

const RENDER_ORDER_MAX_NUM = 9999999;

export class Layer extends EventListener {
  name = "Layer";
  id = null;
  map = null;
  scene = null;
  pickLayerScene = null;
  pickLayerColor = new THREE.Color(0);
  pickMeshScene = null;
  zIndex = 0;
  pickColorNum = 0;

  set visible(visible) {
    this._visible = !!visible;
    !!visible ? this.show() : this.hide();
  }

  get visible() {
    return this._visible;
  }

  get isDisposed() {
    return this._isDisposed;
  }

  constructor({ zIndex = 0, visible = true, event } = {}) {
    super({ event });
    this.id = parseInt(Math.random() * 255 * 255 * 255).toString(16);
    this.zIndex = zIndex;
    this.visible = visible;
    this.pickColorNum = 0;

    this.scene = new THREE.Group();
    this.scene.renderOrder = this.zIndex;
    this.scene.position.z = this.zIndex / 1000;
    // send(this.scene, "add", function (oldFunc, object3D) {
    //   oldFunc(object3D);
    //   object3D.renderOrder = zIndex;
    // });

    this.pickLayerScene = new THREE.Group();
    this.pickLayerScene.renderOrder = this.zIndex;
    this.pickLayerScene.position.z = this.zIndex / 1000;
    // send(this.pickLayerScene, "add", function (oldFunc, object3D) {
    //   oldFunc(object3D);
    //   object3D.renderOrder = zIndex;
    // });

    this.pickMeshScene = new THREE.Group();
    this.pickLayerScene.renderOrder = this.zIndex;
    this.pickMeshScene.position.z = this.zIndex / 1000;
    // send(this.pickMeshScene, "add", function (oldFunc, object3D) {
    //   oldFunc(object3D);
    //   object3D.renderOrder = zIndex;
    // });
  }


  // 设置图层的显示层级
  setZIndex(zIndex) {
    this.zIndex = zIndex;
    this.scene.renderOrder = this.zIndex;
    this.scene.position.z = this.zIndex / 1000;
    this.pickLayerScene.renderOrder = this.zIndex;
    this.pickLayerScene.position.z = this.zIndex / 1000;
    this.pickLayerScene.renderOrder = this.zIndex;
    this.pickMeshScene.position.z = this.zIndex / 1000;
  }

  // 获取拾取物体图层颜色
  getPickMeshColor() {
    return new THREE.Color(++this.pickColorNum);
  }

  // 设置拾取图层颜色
  setPickLayerColor(pickLayerColor) {
    this.pickLayerColor = pickLayerColor;
    for (const v of this.pickLayerScene.children) {
      v.material.setValues({ color: pickLayerColor });
      v.material.needsUpdate = true;
    }
  }

  // 添加到地图回调
  onAdd(map) {
    if (this.map) this.removeFromParent();
    this.map = map;
    this.setPickLayerColor(this.map.getPickLayerColor());
    this.visible = this._visible;
  }

  // 地图中移除回调
  onRemove() {
    this.map = null;
    if (this.scene) this.scene.removeFromParent();
    if (this.pickLayerScene) this.pickLayerScene.removeFromParent();
    if (this.pickMeshScene) this.pickMeshScene.removeFromParent();
  }

  // 从地图中移除
  removeFromParent() {
    if (this.map) {
      this.map.removeLayer(this);
    }
  }

  beforeRender() { }

  afterRender() { }

  // 渲染
  render() { }

  // 事件回调
  on(type, data) {
    this.handleEventListener(type, data);
  }

  // 创建一个立方体
  getBoxMesh(size = 100, color = 0xff0000) {
    //创建一个长方体几何对象Geometry
    const geometry = new THREE.BoxGeometry(size, size, size);
    //创建一个材质对象Material
    const material = new THREE.MeshBasicMaterial({
      side: THREE.DoubleSide, //两面可见
      color: color, //0xff0000设置材质颜色为红色
    });
    // 两个参数分别为几何体geometry、材质material
    const mesh = new THREE.Mesh(geometry, material); //网格模型对象Mesh
    //设置网格模型在三维空间中的位置坐标，默认是坐标原点
    // mesh.position.set(0, 10, 0);
    return mesh;
  }

  // 清除场景
  clearScene() {
    this.pickColorNum = 0;
    if (this.scene) this.scene.remove(...this.scene.children);
    if (this.pickLayerScene) this.pickLayerScene.remove(...this.pickLayerScene.children);
    if (this.pickMeshScene) this.pickMeshScene.remove(...this.pickMeshScene.children);
  }

  // 隐藏
  hide() {
    this._visible = false;
    if (this.scene) this.scene.removeFromParent();
    if (this.pickLayerScene) this.pickLayerScene.removeFromParent();
    if (this.pickMeshScene) this.pickMeshScene.removeFromParent();
  }

  // 显示
  show() {
    this._visible = true;
    if (this.map) {
      if (this.map.world) this.map.world.add(this.scene);
      if (this.map.pickLayerWorld) this.map.pickLayerWorld.add(this.pickLayerScene);
      if (this.map.pickMeshWorld) this.map.pickMeshWorld.add(this.pickMeshScene);
    }
  }

  dispose() {
    this.removeFromParent();
    this._isDisposed = true;
    // TODO
  }

}
