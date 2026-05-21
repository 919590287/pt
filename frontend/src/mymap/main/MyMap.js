import * as THREE from "three";
// 引入轨道控制器扩展库OrbitControls.js
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
//引入性能监视器stats.js
import Stats from "three/addons/libs/stats.module.js";
import { EffectComposer } from "three/examples/jsm/postprocessing/EffectComposer";
import { RenderPass } from "three/examples/jsm/postprocessing/RenderPass";
import { SMAAPass } from "three/examples/jsm/postprocessing/SMAAPass";
import { SSAARenderPass } from "three/examples/jsm/postprocessing/SSAARenderPass";

import { WGS84ToMercator, EPSG4526ToMercator, EARTH_RADIUS } from "../utils/LngLatUtils";
import { EventListener } from "./EventListener";
import { BloomComposer } from "../composer/BloomComposer";

const distance = (form, to) => {
  try {
    return Math.sqrt(Math.pow(form[0] - to[0], 2) + Math.pow(form[1] - to[1], 2)) || 0;
  } catch (error) {
    return 0;
  }
};

export const SCENE_MAP = {
  ENTIRE_SCENE: 0, // 全景图层
  BLOOM_SCENE: 1, // 泛光图层
};

// 事件类型
export const MAP_EVENT = {
  // 鼠标点击没有拾取到模型事件
  HANDLE_NO_PICK: "handle:no:pick",
  // 鼠标点击模型拾取事件
  HANDLE_PICK_LEFT: "handle:pick",
  HANDLE_PICK_RIGHT: "handle:pick:right",
  // 鼠标移动时模型拾取事件
  HANDLE_MOUSE_MOVE_PICK: "handle:mousemove:pick",
  // 鼠标左键点击事件
  HANDLE_CLICK_LEFT: "handle:click",
  // 鼠标右键点击事件
  HANDLE_CLICK_RIGHT: "handle:contextmenu",
  // 鼠标左键按下事件
  HANDLE_MOUSE_LEFT_DOWN: "handle:left:mousedown",
  // 鼠标左键抬起事件
  HANDLE_MOUSE_LEFT_UP: "handle:left:mouseup",
  // 鼠标右键按下事件
  HANDLE_MOUSE_RIGHT_DOWN: "handle:right:mousedown",
  // 鼠标右键抬起事件
  HANDLE_MOUSE_RIGHT_UP: "handle:right:mouseup",
  // 鼠标移动事件
  HANDLE_MOUSE_MOVE: "handle:mousemove",
  // 地图层级缩放事件
  UPDATE_ZOOM: "update:zoom",
  // 地图中心点改变事件
  UPDATE_CENTER: "update:center",
  // 摄像机与观测点距离改变事件
  UPDATE_CAMERA_HEIGHT: "update:camera:height",
  // 摄像机视角改变事件
  UPDATE_CAMERA_ROTATE: "update:camera:rotate",
  // 摄像机位置改变事件
  UPDATE_CAMERA_POSITION: "update:camera:position",
  // 渲染器大小改变事件
  UPDATE_RENDERER_SIZE: "update:renderer:size",
  // 图层渲染前
  LAYER_BEFORE_RENDER: "layer:before:render",
  // 图层渲染后
  LAYER_AFTER_RENDER: "layer:after:render",
  LAYER_LOADING: "layer:loading",
};

export const MAP_ZOOM_RANGE = {
  BASE: 18,
  MIN: 5,
  MAX: 22,
};

// 计算地图zoom和height的一个基数
const MAP_ZOOM_BASE = 18;

// 地图层级缩放高度基数
const MAP_ZOOM_HEIGHT = 400;

export class MyMap extends EventListener {
  name = "map";
  _pickLayerColorNum = 0xffffff;
  openGPUPick = true;

  // 获取摄像机到观测点距离
  get cameraHeight() {
    return this.cameraControls ? Math.round(this.cameraControls.getDistance()) : this.constructor.zoomToHeight(this.zoom);
  }

  // 获取一个拾取图层颜色
  getPickLayerColor() {
    return new THREE.Color(--this._pickLayerColorNum);
  }

  // 性能监视器对象
  get stats() {
    if (!this._stats) {
      //创建性能监视器对象
      this._stats = new Stats();
      if (this.rootDoc) {
        this.rootDoc.appendChild(this._stats.domElement);
      }
    }
    return this._stats;
  }

  // 启用或禁用摄像机水平或垂直旋转。默认值为true。
  // 请注意，为true时可以通过设置minPitch和minRotation来控制单个轴旋转角度
  set enableRotate(enableRotate) {
    this._enableRotate = enableRotate;
    if (this.cameraControls) {
      this.cameraControls.enableRotate = enableRotate;
      // if (!enableRotate) this.setPitchAndRotation(90, 0);
    }
  }
  get enableRotate() {
    return this._enableRotate;
  }

  // 启用或禁用摄像机平移，默认为true。
  set enablePan(enablePan) {
    this._enablePan = enablePan;
    if (this.cameraControls) {
      this.cameraControls.enablePan = enablePan;
    }
  }
  get enablePan() {
    return this._enablePan;
  }

  // 启用或禁用摄像机的缩放。
  set enableZoom(enableZoom) {
    this._enableZoom = enableZoom;
    if (this.cameraControls) {
      this.cameraControls.enableZoom = enableZoom;
    }
  }
  get enableZoom() {
    return this._enableZoom;
  }

  // 相机视线与地图平面的最小夹角 单位：度 0 ~ 90
  set minPitch(minPitch) {
    if (minPitch >= 90) minPitch = 90;
    this._minPitch = minPitch;
    if (this.cameraControls) {
      this.cameraControls.maxPolarAngle = (Math.PI * (90 - minPitch)) / 180;
    }
  }
  get minPitch() {
    return this._minPitch;
  }

  // 获取摄像机旋转角度 rotation: 水平夹角 pitch：垂直夹角
  get cameraRotation() {
    try {
      const height = Math.round(this.cameraControls.getDistance());
      const [px, py, pz] = new THREE.Vector3().subVectors(this.camera.position, this.cameraControls.target);
      let pitch = Math.asin(py / height) || (Math.PI / 2);
      let rotation = Math.atan(px / pz);
      if (px >= 0 && pz >= 0) {
        rotation = rotation;
      } else if (px >= 0 && pz < 0) {
        rotation = Math.PI + rotation;
      } else if (px < 0 && pz < 0) {
        rotation = Math.PI + rotation;
      } else if (px < 0 && pz >= 0) {
        rotation = Math.PI * 2 + rotation;
      }
      return { rotation, pitch, rotationDeg: Math.round(rotation * (180 / Math.PI)), pitchDeg: Math.round(pitch * (180 / Math.PI)) };
    } catch (error) {
      console.log(error);
      return { rotation: 0, pitch: 0, rotationDeg: 0, pitchDeg: 0 };
    }
  }


  // 比例尺 m:px
  get plottingScale() {
    try {
      // const height = this.cameraHeight
      // const { far, fov } = this.camera
      // const width1 = height / (Math.cos((Math.PI * fov) / 180) * 2) * 2
      // const width2 = this.rootDoc.offsetWidth
      // const scale = width1 / width2
      // const [x1, y1] = this.WindowXYToCanvasXY(0, 0)
      // const [x2, y2] = this.WindowXYToCanvasXY(0, 30)
      // const scale = Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2)) / 30
      return this.cameraHeight / 500
    } catch (error) {
      return 1
    }
  }

  constructor({
    rootId,
    center = [12614426, 2646623],
    zoom = 15,
    pitch = 90,
    minPitch = 10,
    rotation = 0,
    minRotation = 360,
    openGPUPick = true,
    noControls = false,
    enableRotate = false,
    enablePan = true,
    enableZoom = true,
    background = 0xd9ecff,
    targetFPS = 60,
    maxPixelRatio = 1.5,
    ssaaSampleLevel = 4,
    ...opt
  }) {
    super(opt);
    // 获取根节点dom
    this.rootDoc = document.getElementById(rootId);
    if (!this.rootDoc) {
      throw new Error("无法获取地图根节点:" + rootId);
    }
    this.rootDoc.style.position = "relative";
    this.rootDoc.style.overflow = "hidden";
    // 图层数组
    this.layers = [];
    // 地图中点经纬度
    this.center = center;
    // 地图缩放层级
    this.zoom = zoom;
    // 设置地图是否3D视角
    this.enableRotate = enableRotate;
    // 设置地图是否平移
    this.enablePan = enablePan;
    // 设置地图是否缩放
    this.enableZoom = enableZoom;
    // 相机视线与地图平面的夹角 单位：度 minPitch ~ 90
    this.pitch = pitch;
    // 相机视线与地图平面的最小夹角 单位：度 0 ~ 90
    this.minPitch = minPitch;
    // 相机与正南方向的夹角 单位：度
    this.rotation = rotation;
    // 相机与正南方向的最小夹角 单位：度 0 ~ 360
    this.minRotation = minRotation;
    // 打开GPU拾取
    this.openGPUPick = openGPUPick;
    // 地图背景色
    this.background = background;
    this.targetFPS = targetFPS;
    this._frameInterval = 1000 / this.targetFPS;
    this._lastRenderTime = -Infinity;
    this.maxPixelRatio = maxPixelRatio;
    this.ssaaSampleLevel = ssaaSampleLevel;

    // 初始化渲染器
    this.initRenderer();
    // 初始化场景
    this.initScene();
    // 初始化光源
    this.initLight();
    // 初始化相机
    this.initCamera();
    // 初始化相机控制器
    if (!noControls) this.initCameraControls();
    this.initComposer();

    // 设置层级
    this.setZoom(zoom);
    // 初始化地图大小监听
    this.ininResize();
    // 设置相机视角
    this.setPitchAndRotation();
    // 开启渲染循环
    this.animation();

    // 初始化鼠标事件监听
    this.initMouseEventListener();
  }

  setBackground(background) {
    this.background = new THREE.Color(background);
    this.scene.background = this.background;
    this.scene.fog.color = this.background;
  }

  // 初始化渲染器
  initRenderer() {
    // 创建渲染器
    this.renderer = new THREE.WebGLRenderer({
      // 设置抗锯齿
      antialias: true,
      // 设置对数深度缓冲区
      logarithmicDepthBuffer: true,
    });
    this.renderer.domElement.style.userSelect = "none";
    this.renderer.domElement.style.position = "absolute";
    this.renderer.domElement.style.top = "0";
    this.renderer.domElement.style.left = "0";
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, this.maxPixelRatio));
    this.rootDoc.appendChild(this.renderer.domElement);
  }

  initComposer() {
    this.composer = new EffectComposer(this.renderer);

    this.renderPass = new RenderPass(this.scene, this.camera);
    this.composer.addPass(this.renderPass);

    // this.bloomComposer = new BloomComposer(this.renderer, this.scene, this.camera, {
    //   layerNum: SCENE_MAP.BLOOM_SCENE,
    // });
    // this.composer.addPass(this.bloomComposer.pass);

    // 获取.setPixelRatio()设置的设备像素比
    // const pixelRatio = this.renderer.getPixelRatio();
    // width、height是canva画布的宽高度
    // this.smaaPass = new SMAAPass(this.rootDoc.clientWidth * pixelRatio, this.rootDoc.clientHeight * pixelRatio);
    // this.composer.addPass(this.smaaPass);
    this.ssaaPass = new SSAARenderPass(this.scene, this.camera);
    this.ssaaPass.sampleLevel = this.ssaaSampleLevel;
    this.ssaaPass.unbiased = true;
    this.composer.addPass(this.ssaaPass);
  }

  // 初始化场景
  initScene() {
    // 创建场景
    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(this.background);
    this.scene.fog = new THREE.Fog(this.background, 1, 1000);

    // 创建渲染地图分组
    this.world = new THREE.Group();
    // 对地图的坐标系进行纠正
    this.world.rotateX(-Math.PI / 2);
    this.world.scale.set(1, 1, 1);
    this.scene.add(this.world);
    // 添加一个模拟3个坐标轴的对象
    // this.scene.add(new THREE.AxesHelper(1000));

    // 创建图层拾取场景
    this.pickLayerScene = new THREE.Scene();
    this.pickLayerWorld = new THREE.Group();
    this.pickLayerWorld.rotateX(-Math.PI / 2);
    this.pickLayerWorld.scale.set(1, 1, 1);
    this.pickLayerScene.add(this.pickLayerWorld);
    // 创建图层拾取渲染器
    this.pickLayerTarget = new THREE.WebGLRenderTarget();

    // 创建模型拾取场景
    this.pickMeshScene = new THREE.Scene();
    this.pickMeshWorld = new THREE.Group();
    this.pickMeshWorld.rotateX(-Math.PI / 2);
    this.pickMeshWorld.scale.set(1, 1, 1);
    this.pickMeshScene.add(this.pickMeshWorld);
    // 创建模型拾取渲染器
    this.pickMeshTarget = new THREE.WebGLRenderTarget();
  }

  // 初始化相机
  initCamera() {
    // 创建相机
    this.camera = new THREE.PerspectiveCamera(60, 1, 1, 3000);
    // 设置相机位置
    this.camera.position.y = 1000;
    this.camera.lookAt(0, 0, 0);

    this.scene.add(this.camera);
  }

  // 初始化光源
  initLight() {
    // 添加环境光线
    const light = new THREE.AmbientLight(0xaaaaaa);
    this.scene.add(light);

    const directionalLight = new THREE.DirectionalLight(0xaaaaaa); // 创建方向光
    directionalLight.position.set(-1500, 4000, 3500); // 设置方向光源位置

    directionalLight.castShadow = true;
    directionalLight.shadow.mapSize.width = 1024;
    directionalLight.shadow.mapSize.height = 1024;
    // 方向光投影近点、远点更新
    directionalLight.shadow.camera.near = 2;
    directionalLight.shadow.camera.far = 10000;

    // 方向光投影边界更新
    directionalLight.shadow.camera.top = 8;
    directionalLight.shadow.camera.bottom = -8;
    directionalLight.shadow.camera.left = -8;
    directionalLight.shadow.camera.right = 8;

    this.scene.add(directionalLight);
  }

  // 添加地图鼠标事件监听
  initMouseEventListener() {
    this.rootDoc.addEventListener("mousemove", (event) => {
      let data = {};
      data.event = event;
      data.windowSize = [this.rootDoc.clientWidth, this.rootDoc.clientHeight];
      data.windowXY = [event.offsetX, event.offsetY];
      data.canvasXY = this.WindowXYToCanvasXY(event.offsetX, event.offsetY);
      data.webMercatorXY = this.CanvasXYToWebMercator(...data.canvasXY);
      this.on(MAP_EVENT.HANDLE_MOUSE_MOVE, data);

      if (!this.MOUSE_MOVE_TIMEOUT) {
        this.MOUSE_MOVE_TIMEOUT = setTimeout(() => {
          this.MOUSE_MOVE_TIMEOUT = null;
          const x = data.event.offsetX;
          const y = data.event.offsetY;
          const pickLayerColor = this.handleGPUPickLayer(x, y);
          const layer = this.layers.find((v) => v.pickLayerColor.getHex() == pickLayerColor);
          this.on(MAP_EVENT.HANDLE_MOUSE_MOVE_PICK, {
            ...data,
            pickColor: layer ? this.handleGPUPickMesh(layer.pickMeshScene, x, y) : 0,
            layerId: layer ? layer.id : null,
          });
        }, 50);
      }
    });

    this.rootDoc.addEventListener("mousedown", (event) => {
      this.mouseDowning = false;
      this.mouseDowned = true;
      this.mousedownEvent = event;

      let data = {};
      data.event = event;
      data.windowSize = [this.rootDoc.clientWidth, this.rootDoc.clientHeight];
      data.windowXY = [event.offsetX, event.offsetY];
      data.canvasXY = this.WindowXYToCanvasXY(event.offsetX, event.offsetY);
      data.webMercatorXY = this.CanvasXYToWebMercator(...data.canvasXY);

      this.mousedownTimeout = setTimeout(() => {
        this.mouseDowning = true;
        if (event.button == 0) {
          this.on(MAP_EVENT.HANDLE_MOUSE_LEFT_DOWN, data);
        } else if (event.button == 2) {
          this.on(MAP_EVENT.HANDLE_MOUSE_RIGHT_DOWN, data);
        }
      }, 200);
    });

    this.rootDoc.addEventListener("mouseup", (event) => {
      let data = {};
      data.event = event;
      data.windowSize = [this.rootDoc.clientWidth, this.rootDoc.clientHeight];
      data.windowXY = [event.offsetX, event.offsetY];
      data.canvasXY = this.WindowXYToCanvasXY(event.offsetX, event.offsetY);
      data.webMercatorXY = this.CanvasXYToWebMercator(...data.canvasXY);

      if (event.button == 0) {
        if (this.mouseDowning) {
          this.mouseDowning = false;
          this.on(MAP_EVENT.HANDLE_MOUSE_LEFT_UP, data);
        } else if (this.mouseDowned) {
          clearTimeout(this.mousedownTimeout);
          if (this.openGPUPick) {
            const x = data.event.offsetX;
            const y = data.event.offsetY;
            const pickLayerColor = this.handleGPUPickLayer(x, y);
            const layer = this.layers.find((v) => v.pickLayerColor.getHex() == pickLayerColor);
            const pickData = {
              ...data,
              pickColor: layer ? this.handleGPUPickMesh(layer.pickMeshScene, x, y) : 0,
              layerId: layer ? layer.id : null,
            };
            this.on(MAP_EVENT.HANDLE_CLICK_LEFT, pickData);
            this.on(MAP_EVENT.HANDLE_PICK_LEFT, pickData);
          } else {
            this.on(MAP_EVENT.HANDLE_CLICK_LEFT, data);
          }
        }
      } else if (event.button == 2) {
        if (this.mouseDowning) {
          this.mouseDowning = false;
          this.on(MAP_EVENT.HANDLE_MOUSE_RIGHT_UP, data);
        } else if (this.mouseDowned) {
          clearTimeout(this.mousedownTimeout);
          if (this.openGPUPick) {
            const x = data.event.offsetX;
            const y = data.event.offsetY;
            const pickLayerColor = this.handleGPUPickLayer(x, y);
            const layer = this.layers.find((v) => v.pickLayerColor.getHex() == pickLayerColor);
            const pickData = {
              ...data,
              pickColor: layer ? this.handleGPUPickMesh(layer.pickMeshScene, x, y) : 0,
              layerId: layer ? layer.id : null,
            };
            this.on(MAP_EVENT.HANDLE_CLICK_RIGHT, pickData);
            this.on(MAP_EVENT.HANDLE_PICK_RIGHT, pickData);
          } else {
            this.on(MAP_EVENT.HANDLE_CLICK_RIGHT, data);
          }
        }
      }

      this.mouseDowned = false;
    });

    this.rootDoc.addEventListener("mouseout", function () {
      this.mouseDowning = false;
      this.mouseDowned = false;
    });
  }

  // 添加地图大小改变事件监听
  ininResize() {
    new ResizeObserver((entries) => {
      // 节流
      if (this._setSizeTimeout) return;
      this._setSizeTimeout = setTimeout(() => {
        const width = this.rootDoc.clientWidth;
        const height = this.rootDoc.clientHeight;
        // 更新摄影机的纵横比
        this.camera.aspect = width / height;
        this.camera.updateProjectionMatrix();
        // 更新渲染器的大小
        this.renderer.setSize(width, height);
        this.composer.setSize(width, height);
        // this.bloomComposer.setSize(width, height);
        // 更新拾取渲染器的大小
        this.pickLayerTarget.setSize(width, height);
        this.pickMeshTarget.setSize(width, height);
        this.on(MAP_EVENT.UPDATE_RENDERER_SIZE, {
          width,
          height,
        });
        this._setSizeTimeout = null;
      }, 1000 / 120);
    }).observe(this.rootDoc);
  }

  // 设置相机视线与地图平面的夹角、相机与正南方向的夹角
  setPitchAndRotation(pitch = this.pitch, rotation = this.rotation) {
    const oldPitch = this.pitch;
    const oldRotation = this.rotation;
    if (pitch > 90) pitch = 90;
    if (pitch < this.minPitch) pitch = this.minPitch;
    // 相机视线与地图平面的夹角
    this.pitch = pitch;
    // 相机与正南方向的夹角
    this.rotation = rotation;
    const l1 = Math.round(this.cameraControls.getDistance());
    const l2 = l1 * Math.cos((Math.PI / 180) * pitch);

    let z = l1 * Math.sin((Math.PI / 180) * pitch);
    let x = l2 * Math.sin((Math.PI / 180) * rotation);
    let y = l2 * Math.cos((Math.PI / 180) * rotation);
    this.camera.position.set(x, z, y);
    this.camera.lookAt(0, 0, 0);
    if (this.cameraControls) {
      this.cameraControls.target.set(0, 0, 0);
      this.cameraControls.update();
    }

    this.on(MAP_EVENT.UPDATE_CAMERA_ROTATE, {
      oldPitch: oldPitch,
      newPitch: this.pitch,
      oldRotation: oldRotation,
      newRotation: this.rotation,
    });
  }

  // 添加地图摄像机控制器
  initCameraControls() {
    // 设置相机控件轨道控制器OrbitControls
    this.cameraControls = new OrbitControls(this.camera, this.renderer.domElement);
    // 启用或禁用摄像机水平或垂直旋转。默认值为true。
    this.cameraControls.enableRotate = this._enableRotate;
    // 启用或禁用摄像机平移，默认为true。
    this.cameraControls.enablePan = this._enablePan;
    // 启用或禁用摄像机的缩放。
    this.cameraControls.enableZoom = this._enableZoom;
    // 限制垂直旋转角度
    this.cameraControls.maxPolarAngle = (Math.PI * (90 - this._minPitch)) / 180;
    // 限制水平旋转角度
    // this.cameraControls.maxAzimuthAngle = 0;
    // this.cameraControls.minAzimuthAngle = 0;
    // 是否使用屏幕空间旋转。默认值为false。
    this.cameraControls.screenSpacePanning = false;

    this.cameraControls.minDistance = this.constructor.zoomToHeight(MAP_ZOOM_RANGE.MAX);
    this.cameraControls.maxDistance = this.constructor.zoomToHeight(MAP_ZOOM_RANGE.MIN);
    //修改鼠标按键
    // this.cameraControls.mouseButtons = {
    //   LEFT: THREE.MOUSE.PAN,
    //   MIDDLE: THREE.MOUSE.DOLLY,
    //   RIGHT: THREE.MOUSE.ROTATE,
    // };

    this.cameraControls.addEventListener("change", (res) => {
      const height = Math.round(this.cameraControls.getDistance());
      const zoom = this.constructor.heightToZoom(height);
      this.scene.fog.near = height * 2;
      this.scene.fog.far = height * 3.5;
      this.camera.near = height / 1000;
      this.camera.far = height * 3.5;
      this.camera.updateProjectionMatrix();
      this.setZoom(zoom, true);

      let { pitch, rotation } = this.cameraRotation;
      pitch = Math.round(pitch * (180 / Math.PI));
      rotation = Math.round(rotation * (180 / Math.PI));

      if (Math.abs(this.pitch - pitch) > 1 || Math.abs(this.rotation - rotation) > 1) {
        this.on(MAP_EVENT.UPDATE_CAMERA_ROTATE, {
          oldPitch: this.pitch,
          newPitch: pitch,
          oldRotation: this.rotation,
          newRotation: rotation,
        });
        this.pitch = pitch;
        this.rotation = rotation;
      }

      this.on(MAP_EVENT.UPDATE_CAMERA_HEIGHT, height);
      const [x, y, z] = this.cameraControls.target;
      this.on(MAP_EVENT.UPDATE_CAMERA_POSITION, {
        position: [x, y, z],
        webMercator: this.CanvasXYToWebMercator(x, -z),
      });
    });

    this.cameraControls.addEventListener("end", (res) => {
      const [x, y, z] = this.cameraControls.target;
      if (Math.abs(x) > 0.1 || Math.abs(y) > 0.1) {
        const [lng, lat] = this.CanvasXYToWebMercator(x, -z);
        const position = this.camera.position;
        this.camera.position.set(position.x - x, position.y - y, position.z - z);
        this.camera.lookAt(0, 0, 0);
        this.cameraControls.target.set(0, 0, 0);
        this.setCenter([lng, lat]);
      }
    });
  }

  // 拾取逻辑：
  // 先对图层进行拾取
  // 拾取到图层后再对图层里的模型进行拾取

  // 对图层进行拾取
  handleGPUPickLayer(x, y) {
    // 获取点击位置的坐标
    // const x = data.event.offsetX;
    // const y = data.event.offsetY;
    this.renderer.setRenderTarget(this.pickLayerTarget);
    this.renderer.render(this.pickLayerScene, this.camera);
    this.renderer.setRenderTarget(null);
    // 读取点击位置的颜色
    const pixelBufferLayer = new Uint8Array(4);
    this.renderer.readRenderTargetPixels(this.pickLayerTarget, x, this.pickLayerTarget.height - y, 1, 1, pixelBufferLayer);
    // 将颜色信息转换回对象的唯一标识
    const pickLayerColor = (pixelBufferLayer[0] << 16) | (pixelBufferLayer[1] << 8) | pixelBufferLayer[2];
    return pickLayerColor;
  }

  // 对图层里的模型进行拾取
  handleGPUPickMesh(scene, x, y) {
    // 获取点击位置的坐标
    // const x = data.event.offsetX;
    // const y = data.event.offsetY;
    if (scene) {
      this.pickMeshWorld.remove(...this.pickMeshWorld.children);
      this.pickMeshWorld.add(scene);

      this.renderer.setRenderTarget(this.pickMeshTarget);
      this.renderer.render(this.pickMeshScene, this.camera);
      this.renderer.setRenderTarget(null);

      // 读取点击位置的颜色
      const pixelBufferMesh = new Uint8Array(4);
      this.renderer.readRenderTargetPixels(this.pickMeshTarget, x, this.pickMeshTarget.height - y, 1, 1, pixelBufferMesh);
      // 将颜色信息转换回对象的唯一标识
      const pickColor = (pixelBufferMesh[0] << 16) | (pixelBufferMesh[1] << 8) | pixelBufferMesh[2];
      this.pickMeshWorld.remove(scene);
      return pickColor;
    } else {
      return 0;
    }
  }

  // 添加图层
  addLayer(layer) {
    if (layer.isDisposed) {
      this.removeLayer(layer);
      console.error("图层已销毁", layer);
    } else {
      const index = this.layers.findIndex((v) => v.id == layer.id);
      if (index == -1) {
        layer.onAdd(this);
        this.layers.push(layer);
        this.layers.sort((a, b) => a.zIndex - b.zIndex);
      }
    }
  }

  // 移除图层
  removeLayer(layer) {
    let index = this.layers.findIndex((v) => v.id == layer.id);
    if (index > -1) {
      this.layers.splice(index, 1);
      layer.onRemove();
    }
  }

  // 事件通知
  on(type, data) {
    this.handleEventListener(type, data);
    for (const layer of this.layers) {
      try {
        if (layer.isDisposed) {
          this.removeLayer(layer);
          console.error("图层已销毁", layer);
        } else {
          layer.on(type, data);
        }
      } catch (error) {
        console.error(error);
      }
    }
  }

  // 渲染前事件
  beforeRender() {
    this.on(MAP_EVENT.LAYER_BEFORE_RENDER, this);
    for (const layer of this.layers) {
      try {
        if (layer.isDisposed) {
          this.removeLayer(layer);
          console.error("图层已销毁", layer);
        } else {
          layer.beforeRender(this);
        }
      } catch (error) {
        console.error(error);
      }
    }
  }

  // 渲染后事件
  afterRender() {
    for (const layer of this.layers) {
      try {
        if (layer.isDisposed) {
          this.removeLayer(layer);
          console.error("图层已销毁", layer);
        } else {
          layer.afterRender(this);
        }
      } catch (error) {
        console.error(error);
      }
    }
    this.on(MAP_EVENT.LAYER_AFTER_RENDER, this);
  }

  // 渲染事件
  render() {
    for (const layer of this.layers) {
      try {
        if (layer.isDisposed) {
          this.removeLayer(layer);
          console.error("图层已销毁", layer);
        } else {
          layer.render(this);
        }
      } catch (error) {
        console.error(error);
      }
    }
    // this.bloomComposer.render();
    this.composer.render();
  }

  // 渲染循环
  animation(timestamp = 0) {
    if (this.isDisposed) return;
    if (timestamp - this._lastRenderTime >= this._frameInterval) {
      this._lastRenderTime = timestamp;
      this.beforeRender();
      this.render();
      this.afterRender();
    }
    // this.stats.update();
    window.requestAnimationFrame((time) => {
      this.animation(time);
      for (let i = 0, l = this._nextFrameFunList.length; i < l; i++) {
        try {
          const fun = this._nextFrameFunList[i];
          fun();
        } catch (error) {
          console.log(error);
        }
      }
      this._nextFrameFunList.length = 0;
    });
  }

  _nextFrameFunList = [];
  nextFrame(fun) {
    this._nextFrameFunList.push(fun);
  }

  // 销毁地图，释放内存
  dispose() {
    this.isDisposed = true;
    // TODO
    const layers = [...this.layers];
    for (const layer of layers) {
      this.removeLayer(layer);
      layer.dispose();
    }
    // 清空场景和相机
    this.scene.clear();
    this.world.clear();
    this.pickLayerScene.clear();
    this.pickLayerWorld.clear();

    // 清空渲染器和其DOM元素
    this.rootDoc.removeChild(this.renderer.domElement);
    this.renderer.domElement = null;
    this.renderer.forceContextLoss();
    this.renderer.dispose();

    // 清空渲染器和其DOM元素
    this.rootDoc.removeChild(this.css2dRenderer.domElement);
    this.css2dRenderer.domElement = null;
    this.css2dRenderer.dispose();
  }


  // 设置相机到观察点的距离
  setCameraHeight(height, noChangeZoom) {
    if (this.camera) {
      this.camera.position.y = height;
      this.scene.fog.near = height * 2;
      this.scene.fog.far = height * 3.5;
      this.camera.near = height / 1000;
      this.camera.far = height * 3.5;
      this.camera.updateProjectionMatrix();
      this.on(MAP_EVENT.UPDATE_CAMERA_HEIGHT, height);

      if (!noChangeZoom) {
        let zoom = this.constructor.heightToZoom(height);
        this.setZoom(zoom, true);
      }
    }
  }

  // 设置缩放层级
  setZoom(zoom, noChangeHeight) {
    // zoom = Math.floor(zoom * 1000) / 1000;
    if (zoom < MAP_ZOOM_RANGE.MIN) {
      zoom = MAP_ZOOM_RANGE.MIN;
    } else if (zoom > MAP_ZOOM_RANGE.MAX) {
      zoom = MAP_ZOOM_RANGE.MAX;
    }
    if (!noChangeHeight && this.camera) {
      let height2 = this.constructor.zoomToHeight(zoom);
      this.setCameraHeight(height2);
    }
    if (this.zoom != zoom) {
      this.zoom = zoom;
      this.on(MAP_EVENT.UPDATE_ZOOM, zoom);
    }
  }

  // 设置地图中心点
  setCenter(center) {
    if (JSON.stringify(this.center) != JSON.stringify(center)) {
      this.center = center;
      this.on(MAP_EVENT.UPDATE_CENTER);
    }
  }

  // 根据WebMercator坐标数组设置最佳缩放层级和中心点
  setFitZoomAndCenterByPoints(list) {
    const { height, center, zoom } = this.getFitZoomAndCenter(list);
    this.setCenter(center);
    this.setZoom(zoom);
    return {
      height,
      center,
      zoom,
    };
  }

  // 获取最佳缩放层级和中心点
  getFitZoomAndCenter(list) {
    if (list.length == 0) {
      return {
        height: this.cameraHeight,
        center: [...this.center],
        zoom: this.zoom,
      };
    }
    let maxX = 0,
      maxY = 0,
      minX = Number.MAX_SAFE_INTEGER,
      minY = Number.MAX_SAFE_INTEGER;
    for (const point of list) {
      maxX = Math.max(maxX, point[0]);
      maxY = Math.max(maxY, point[1]);
      minX = Math.min(minX, point[0]);
      minY = Math.min(minY, point[1]);
    }

    let lenX = maxX - minX;
    let lenY = maxY - minY;
    let { minX: _minX, minY: _minY, maxX: _maxX, maxY: _maxY } = this.getWindowRangeAndWebMercator();

    let _lenX = _maxX - _minX;
    let _lenY = _maxY - _minY;
    let _height = this.cameraHeight;

    let height = Math.max((_height * lenX * 1.5) / _lenX, (_height * lenY * 1.5) / _lenY);
    return {
      height: height,
      center: [(maxX + minX) / 2, (maxY + minY) / 2],
      zoom: this.constructor.heightToZoom(height),
    };
  }

  // 获取窗口范围并转换成WebMercator
  getWindowRangeAndWebMercator() {
    const width = this.rootDoc.clientWidth;
    const height = this.rootDoc.clientHeight;
    const center = [...this.center];
    const topLeft = this.WindowXYToWebMercator(0, 0) || center;
    const bottomLeft = this.WindowXYToWebMercator(0, height) || center;
    const bottomRight = this.WindowXYToWebMercator(width, height) || center;
    const topRight = this.WindowXYToWebMercator(width, 0) || center;
    const maxX = Math.max(topLeft[0], bottomLeft[0], bottomRight[0], topRight[0]);
    const minX = Math.min(topLeft[0], bottomLeft[0], bottomRight[0], topRight[0]);
    const maxY = Math.max(topLeft[1], bottomLeft[1], bottomRight[1], topRight[1]);
    const minY = Math.min(topLeft[1], bottomLeft[1], bottomRight[1], topRight[1]);
    return {
      topLeft: topLeft,
      bottomLeft: bottomLeft,
      bottomRight: bottomRight,
      topRight: topRight,
      maxX: maxX,
      minX: minX,
      maxY: maxY,
      minY: minY,
      width: distance(topLeft, topRight),
      height: distance(topLeft, bottomLeft),
    };
  }

  getTileRangeByZoom(zoom) {
    const [mapCenterX, mapCenterY] = this.center;
    const { far, fov } = this.camera;
    const width = far / (Math.cos((Math.PI * fov) / 180) * 2);
    const [row, col] = [Math.floor(((EARTH_RADIUS + mapCenterX) * Math.pow(2, zoom)) / (EARTH_RADIUS * 2)), Math.floor(((EARTH_RADIUS - mapCenterY) * Math.pow(2, zoom)) / (EARTH_RADIUS * 2))];
    const tileSize = (EARTH_RADIUS * 2) / Math.pow(2, zoom);
    const radius = Math.ceil(width / tileSize);

    const max_row_col = Math.pow(2, zoom);

    let rowStart = row - radius;
    if (rowStart < 0) rowStart = 0;
    let rowEnd = row + radius;
    if (rowEnd > max_row_col) rowEnd = max_row_col;

    let colStart = col - radius;
    if (colStart < 0) colStart = 0;
    let colEnd = col + radius;
    if (colEnd > max_row_col) colEnd = max_row_col;

    const x1 = (rowStart * (EARTH_RADIUS * 2)) / Math.pow(2, zoom) - EARTH_RADIUS;
    const y1 = EARTH_RADIUS - (colStart * (EARTH_RADIUS * 2)) / Math.pow(2, zoom);
    const x2 = ((rowEnd + 1) * (EARTH_RADIUS * 2)) / Math.pow(2, zoom) - EARTH_RADIUS;
    const y2 = EARTH_RADIUS - ((colEnd + 1) * (EARTH_RADIUS * 2)) / Math.pow(2, zoom);

    return {
      row: [rowStart, rowEnd],
      col: [colStart, colEnd],
      minX: Math.min(x1, x2),
      maxX: Math.max(x1, x2),
      minY: Math.min(y1, y2),
      maxY: Math.max(y1, y2),
      center: [mapCenterX, mapCenterY],
      size: tileSize,
      zoom: zoom,
    };
  }

  // 把渲染坐标转换成WebMercator
  CanvasXYToWebMercator(x, y, cx = this.center[0], cy = this.center[1]) {
    return [x + cx, y + cy];
  }

  // 把WebMercator转换成渲染坐标
  WebMercatorToCanvasXY(x, y, cx = this.center[0], cy = this.center[1]) {
    return [x - cx, y - cy];
  }

  // 把WGS84转换成渲染坐标
  WGS84ToCanvasXY(lng, lat, cx = this.center[0], cy = this.center[1]) {
    return this.WebMercatorToCanvasXY(...WGS84ToMercator(lng, lat, cx, cy));
  }

  // 把EPSG4526转换成渲染坐标
  EPSG4526ToCanvasXY(lng, lat, cx = this.center[0], cy = this.center[1]) {
    return this.WebMercatorToCanvasXY(...EPSG4526ToMercator(lng, lat, cx, cy));
  }

  // 把窗口坐标换成WebMercator
  WindowXYToWebMercator(x, y) {
    let [x1, y1] = this.WindowXYToCanvasXY(x, y);
    if (x1 == -Infinity) x1 = -3.5 * this.cameraHeight;
    if (x1 == Infinity) x1 = 3.5 * this.cameraHeight;
    if (y1 == Infinity) y1 = -3.5 * this.cameraHeight;
    if (y1 == -Infinity) y1 = 3.5 * this.cameraHeight;
    return this.CanvasXYToWebMercator(x1, y1);
  }

  // 把窗口坐标换成渲染坐标
  WindowXYToCanvasXY(x, y) {
    const origin = new THREE.Vector3().setFromMatrixPosition(this.camera.matrixWorld);
    const direction = new THREE.Vector3((x / this.rootDoc.clientWidth) * 2 - 1, -(y / this.rootDoc.clientHeight) * 2 + 1, 0.5).unproject(this.camera);

    const y3 = 0;
    const px = ((origin.x - direction.x) / (origin.y - direction.y)) * (y3 - direction.y) + direction.x;
    const pz = ((origin.z - direction.z) / (origin.y - direction.y)) * (y3 - direction.y) + direction.z;

    return [px, -pz];
  }

  static heightToZoom(height) {
    let zoom = MAP_ZOOM_BASE - Math.LOG2E * Math.log(height / MAP_ZOOM_HEIGHT);
    return zoom;
  }

  static zoomToHeight(zoom) {
    return MAP_ZOOM_HEIGHT * Math.pow(2, MAP_ZOOM_BASE - zoom);
  }
}
