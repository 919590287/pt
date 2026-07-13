import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { mergeGeometries, mergeVertices } from "three/addons/utils/BufferGeometryUtils.js";

const EARTH_RADIUS = 6378137.0;
const WEB_MERCATOR_HALF_WORLD = Math.PI * EARTH_RADIUS;
const WEB_MERCATOR_WORLD_SIZE = WEB_MERCATOR_HALF_WORLD * 2;
const MERCATOR_UNIT_PER_WEB_MERCATOR_METER = 1 / WEB_MERCATOR_WORLD_SIZE;
const MIN_INSTANCE_CAPACITY = 16;
const DEFAULT_MODEL_WORLD_SCALE = 4;
const DEBUG_PUBLISH_INTERVAL_MS = 250;
// 调试通道（dataset 写入/JSON.stringify）默认关闭，仅 window.APP_CONFIG.debug 时开启
const DEBUG_CHANNEL_ENABLED = typeof window !== "undefined" && Boolean(window.APP_CONFIG?.debug);
const ORIGIN_REBASE_DEFAULT_METERS = 50000;
const SMOOTH_MIN_ZOOM = 15.3;
const SMOOTH_SNAP_METERS = 900;
const MODE_INDEX_TO_KEY = ["bus", "subway", "car"];
const DEG_TO_RAD = Math.PI / 180;
const INSTANCE_Z_METERS = 0.18;
const LOW_ZOOM_SCALE_PIVOT = 13.6;
const LOW_ZOOM_SCALE_MAX = 48;
const VEHICLE_CULL_PADDING_METERS = 600;

function vehicleModelUrl(fileName) {
  const baseUrl =
    typeof window !== "undefined" && window.APP_CONFIG?.vehicleModelsBaseUrl
      ? window.APP_CONFIG.vehicleModelsBaseUrl
      : "/models/vehicles";
  return `${String(baseUrl).replace(/\/+$/, "")}/${fileName}`;
}

const VEHICLE_MODEL_CONFIG = {
  bus: {
    url: vehicleModelUrl("montreal-bus.glb"),
    dimensions: [12.0, 2.65, 3.25],
    displayScale: 0.78,
    bakeYaw: THREE.MathUtils.degToRad(136),
  },
  subway: {
    url: vehicleModelUrl("tram.glb"),
    dimensions: [22.0, 3.05, 3.65],
  },
  car: {
    url: vehicleModelUrl("car.glb"),
    dimensions: [4.65, 1.9, 1.55],
    yawOffset: Math.PI,
  },
};

// 渲染像素比：优先读取 runtime-config 的 mapPixelRatio 降级开关（clamp 到 [1, 2]），
// 未配置时保持默认行为 min(devicePixelRatio, 2)。
function rendererPixelRatio() {
  const configured = typeof window !== "undefined" ? Number(window.APP_CONFIG?.mapPixelRatio) : NaN;
  if (Number.isFinite(configured) && configured > 0) {
    return Math.max(1, Math.min(2, configured));
  }
  return Math.min((typeof window !== "undefined" && window.devicePixelRatio) || 1, 2);
}

function lowZoomScaleMultiplier(zoom) {
  const value = Number(zoom);
  if (!Number.isFinite(value) || value >= LOW_ZOOM_SCALE_PIVOT) return 1;
  return Math.max(1, Math.min(LOW_ZOOM_SCALE_MAX, Math.pow(2, LOW_ZOOM_SCALE_PIVOT - value)));
}

function nextCapacity(count) {
  let capacity = MIN_INSTANCE_CAPACITY;
  while (capacity < count) {
    capacity *= 2;
  }
  return capacity;
}

function webMercatorToMercatorUnit(x, y) {
  return [
    (Number(x) + WEB_MERCATOR_HALF_WORLD) / WEB_MERCATOR_WORLD_SIZE,
    (WEB_MERCATOR_HALF_WORLD - Number(y)) / WEB_MERCATOR_WORLD_SIZE,
  ];
}

function webMercatorToLngLat(x, y) {
  const lng = (Number(x) / EARTH_RADIUS) * (180 / Math.PI);
  const lat = (2 * Math.atan(Math.exp(Number(y) / EARTH_RADIUS)) - Math.PI / 2) * (180 / Math.PI);
  return [lng, lat];
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, Number(value) || 0));
}

function normalizeAngleDegrees(value) {
  let angle = Number(value) || 0;
  while (angle <= -180) angle += 360;
  while (angle > 180) angle -= 360;
  return angle;
}

function lerpAngleDegrees(from, to, alpha) {
  const delta = normalizeAngleDegrees(to - from);
  return normalizeAngleDegrees(from + delta * alpha);
}

function writeInstanceTransform(target, offset, x, y, angleRad) {
  target[offset] = x;
  target[offset + 1] = y;
  target[offset + 2] = Math.cos(angleRad);
  target[offset + 3] = Math.sin(angleRad);
}

function writeInstanceSegment(xyTarget, infoTarget, index, sx, sy, ex, ey, startTime, endTime, angleRad) {
  const xyOffset = index * 4;
  xyTarget[xyOffset] = sx;
  xyTarget[xyOffset + 1] = sy;
  xyTarget[xyOffset + 2] = ex;
  xyTarget[xyOffset + 3] = ey;
  const infoOffset = index * 4;
  infoTarget[infoOffset] = startTime;
  infoTarget[infoOffset + 1] = endTime;
  infoTarget[infoOffset + 2] = Math.cos(angleRad);
  infoTarget[infoOffset + 3] = Math.sin(angleRad);
}

function loadGltf(url) {
  const loader = new GLTFLoader();
  return new Promise((resolve, reject) => {
    loader.load(url, resolve, undefined, reject);
  });
}

function computePartsBox(parts) {
  const box = new THREE.Box3();
  let hasGeometry = false;
  for (const part of parts) {
    part.geometry.computeBoundingBox();
    if (part.geometry.boundingBox) {
      box.union(part.geometry.boundingBox);
      hasGeometry = true;
    }
  }
  return hasGeometry ? box : null;
}

function optimizeStaticGeometry(geometry) {
  try {
    const optimized = mergeVertices(geometry, 1e-5);
    if (optimized && optimized !== geometry) {
      geometry.dispose?.();
      optimized.computeBoundingBox();
      optimized.computeBoundingSphere();
      if (!optimized.getAttribute("normal")) {
        optimized.computeVertexNormals();
      }
      return optimized;
    }
  } catch {
    // Keep the original geometry if an exotic GLB attribute cannot be welded safely.
  }
  return geometry;
}

function canBakeMaterialColor(material) {
  if (!material || Array.isArray(material)) return false;
  if (material.map || material.alphaMap || material.normalMap || material.roughnessMap || material.metalnessMap) return false;
  const opacity = Number.isFinite(material.opacity) ? material.opacity : 1;
  return !material.transparent && opacity >= 0.999;
}

function applyVertexColor(geometry, material) {
  const position = geometry.getAttribute("position");
  if (!position?.count) return false;
  const color = material?.color || new THREE.Color(1, 1, 1);
  const colors = new Float32Array(position.count * 3);
  for (let i = 0; i < position.count; i++) {
    const offset = i * 3;
    colors[offset] = color.r;
    colors[offset + 1] = color.g;
    colors[offset + 2] = color.b;
  }
  geometry.setAttribute("color", new THREE.BufferAttribute(colors, 3));
  return true;
}

function mergeVertexColoredParts(parts, materialSet, mode) {
  const colored = [];
  const passthrough = [];
  for (const part of parts) {
    if (!canBakeMaterialColor(part.material) || !applyVertexColor(part.geometry, part.material)) {
      passthrough.push(part);
      continue;
    }
    colored.push(part);
  }
  if (colored.length <= 1) {
    return parts;
  }
  try {
    let geometry = mergeGeometries(colored.map((part) => part.geometry), false);
    if (!geometry) return parts;
    geometry = optimizeStaticGeometry(geometry);
    geometry.computeBoundingBox();
    geometry.computeBoundingSphere();
    colored.forEach((part) => part.geometry.dispose?.());
    const material = new THREE.MeshBasicMaterial({
      name: `${mode}-baked-vertex-color`,
      color: 0xffffff,
      vertexColors: true,
    });
    materialSet.add(material);
    return [
      ...passthrough,
      {
        geometry,
        material,
        name: colored.map((part) => part.name).join("+"),
      },
    ];
  } catch (error) {
    console.warn("[VehicleModelLayer] merge vertex-colored GLB parts failed, rendering original parts:", error);
    return parts;
  }
}

function collectMaterials(material, target) {
  if (Array.isArray(material)) {
    material.forEach((item) => collectMaterials(item, target));
  } else if (material) {
    target.add(material);
  }
}

function disposeMaterial(material) {
  const materials = Array.isArray(material) ? material : [material];
  for (const item of materials) {
    if (!item) continue;
    for (const value of Object.values(item)) {
      if (value?.isTexture) {
        value.dispose?.();
      }
    }
    item.dispose?.();
  }
}

function disposeMaterialInstance(material) {
  const materials = Array.isArray(material) ? material : [material];
  for (const item of materials) {
    item?.dispose?.();
  }
}

const VEHICLE_LIGHTING = {
  hemiSky: new THREE.Color(0xffffff).multiplyScalar(0.86),
  hemiGround: new THREE.Color(0x8b95a5).multiplyScalar(0.34),
  keyDir: new THREE.Vector3(120, -80, 180).normalize(),
  keyColor: new THREE.Color(0xffffff).multiplyScalar(0.72),
  fillDir: new THREE.Vector3(-160, 90, 120).normalize(),
  fillColor: new THREE.Color(0xffffff).multiplyScalar(0.26),
};

function createFastInstancedMaterial(material) {
  if (Array.isArray(material)) {
    return material.map((item) => createFastInstancedMaterial(item));
  }
  const color = material?.color?.clone?.() || new THREE.Color(1, 1, 1);
  const opacity = Number.isFinite(material?.opacity) ? material.opacity : 1;
  const map = material?.map || null;
  const vertexColors = Boolean(material?.vertexColors);
  const next = new THREE.ShaderMaterial({
    name: `${material?.name || "vehicle"}-fast-instanced`,
    defines: {
      ...(map ? { USE_VEHICLE_MAP: "" } : {}),
      ...(vertexColors ? { USE_VEHICLE_COLOR: "" } : {}),
    },
    uniforms: {
      diffuse: { value: color },
      opacity: { value: opacity },
      map: { value: map },
      vehicleScale: { value: DEFAULT_MODEL_WORLD_SCALE },
      vehicleZ: { value: INSTANCE_Z_METERS },
      vehicleTime: { value: 0 },
      useGpuTrajectory: { value: 0 },
      hemiSkyColor: { value: VEHICLE_LIGHTING.hemiSky },
      hemiGroundColor: { value: VEHICLE_LIGHTING.hemiGround },
      keyLightDirection: { value: VEHICLE_LIGHTING.keyDir },
      keyLightColor: { value: VEHICLE_LIGHTING.keyColor },
      fillLightDirection: { value: VEHICLE_LIGHTING.fillDir },
      fillLightColor: { value: VEHICLE_LIGHTING.fillColor },
    },
    vertexShader: `
      attribute vec4 instanceTransform;
      attribute vec4 instanceSegmentXY;
      attribute vec4 instanceSegmentInfo;
      uniform float vehicleScale;
      uniform float vehicleZ;
      uniform float vehicleTime;
      uniform float useGpuTrajectory;
      uniform vec3 hemiSkyColor;
      uniform vec3 hemiGroundColor;
      uniform vec3 keyLightDirection;
      uniform vec3 keyLightColor;
      uniform vec3 fillLightDirection;
      uniform vec3 fillLightColor;
      varying vec3 vVehicleLight;
      varying float vVehicleActive;
      #ifdef USE_VEHICLE_MAP
        varying vec2 vVehicleUv;
      #endif
      #ifdef USE_VEHICLE_COLOR
        attribute vec3 color;
        varying vec3 vVehicleColor;
      #endif
      vec2 gjRotate2D(vec2 value, float c, float s) {
        return vec2(value.x * c - value.y * s, value.x * s + value.y * c);
      }
      void main() {
        vec2 instanceXY = instanceTransform.xy;
        float c = instanceTransform.z;
        float s = instanceTransform.w;
        vVehicleActive = 1.0;
        if (useGpuTrajectory > 0.5) {
          float startTime = instanceSegmentInfo.x;
          float endTime = instanceSegmentInfo.y;
          float duration = max(endTime - startTime, 0.001);
          float ratio = clamp((vehicleTime - startTime) / duration, 0.0, 1.0);
          instanceXY = mix(instanceSegmentXY.xy, instanceSegmentXY.zw, ratio);
          c = instanceSegmentInfo.z;
          s = instanceSegmentInfo.w;
          vVehicleActive = step(startTime, vehicleTime) * (1.0 - step(endTime, vehicleTime));
        }
        vec3 localPosition = position;
        vec2 xy = gjRotate2D(localPosition.xy, c, s) * vehicleScale + instanceXY;
        vec3 transformed = vec3(xy, localPosition.z * vehicleScale + vehicleZ);
        vec3 localNormal = normal;
        vec3 vehicleNormal = normalize(vec3(gjRotate2D(localNormal.xy, c, s), localNormal.z));
        float hemi = vehicleNormal.z * 0.5 + 0.5;
        vec3 light = mix(hemiGroundColor, hemiSkyColor, hemi);
        light += keyLightColor * max(dot(vehicleNormal, keyLightDirection), 0.0);
        light += fillLightColor * max(dot(vehicleNormal, fillLightDirection), 0.0);
        vVehicleLight = clamp(light, vec3(0.24), vec3(1.72));
        #ifdef USE_VEHICLE_MAP
          vVehicleUv = uv;
        #endif
        #ifdef USE_VEHICLE_COLOR
          vVehicleColor = color;
        #endif
        gl_Position = projectionMatrix * modelViewMatrix * vec4(transformed, 1.0);
      }
    `,
    fragmentShader: `
      uniform vec3 diffuse;
      uniform float opacity;
      #ifdef USE_VEHICLE_MAP
        uniform sampler2D map;
        varying vec2 vVehicleUv;
      #endif
      #ifdef USE_VEHICLE_COLOR
        varying vec3 vVehicleColor;
      #endif
      varying vec3 vVehicleLight;
      varying float vVehicleActive;
      void main() {
        if (vVehicleActive < 0.5) discard;
        vec4 base = vec4(diffuse, opacity);
        #ifdef USE_VEHICLE_MAP
          base *= texture2D(map, vVehicleUv);
        #endif
        #ifdef USE_VEHICLE_COLOR
          base.rgb *= vVehicleColor;
        #endif
        if (base.a < 0.02) discard;
        vec3 color = base.rgb * vVehicleLight;
        gl_FragColor = vec4(color, base.a);
        #include <colorspace_fragment>
      }
    `,
    transparent: Boolean(material?.transparent) || opacity < 0.999,
    depthTest: material?.depthTest !== false,
    depthWrite: material?.transparent ? false : material?.depthWrite !== false,
    side: material?.side ?? THREE.FrontSide,
    alphaTest: Math.max(0, Number(material?.alphaTest) || 0),
  });
  next.userData.vehicleUniformValues = {
    scale: DEFAULT_MODEL_WORLD_SCALE,
    z: INSTANCE_Z_METERS,
    time: 0,
    useGpuTrajectory: 0,
  };
  next.userData.vehicleUniforms = next.uniforms;
  return next;
}

function setFastMaterialUniforms(material, scale, options = {}) {
  const materials = Array.isArray(material) ? material : [material];
  const time = Number(options.time);
  const useGpuTrajectory = options.useGpuTrajectory ? 1 : 0;
  for (const item of materials) {
    if (!item) continue;
    if (item.userData?.vehicleUniformValues) {
      item.userData.vehicleUniformValues.scale = scale;
      item.userData.vehicleUniformValues.z = INSTANCE_Z_METERS;
      if (Number.isFinite(time)) item.userData.vehicleUniformValues.time = time;
      item.userData.vehicleUniformValues.useGpuTrajectory = useGpuTrajectory;
    }
    const uniforms = item.userData?.vehicleUniforms;
    if (uniforms?.vehicleScale) uniforms.vehicleScale.value = scale;
    if (uniforms?.vehicleZ) uniforms.vehicleZ.value = INSTANCE_Z_METERS;
    if (uniforms?.vehicleTime && Number.isFinite(time)) uniforms.vehicleTime.value = time;
    if (uniforms?.useGpuTrajectory) uniforms.useGpuTrajectory.value = useGpuTrajectory;
  }
}

function createInstancedGeometry(sourceGeometry, instanceTransform, instanceSegmentXY, instanceSegmentInfo) {
  const geometry = new THREE.InstancedBufferGeometry();
  geometry.copy(sourceGeometry);
  geometry.setAttribute("instanceTransform", instanceTransform);
  geometry.setAttribute("instanceSegmentXY", instanceSegmentXY);
  geometry.setAttribute("instanceSegmentInfo", instanceSegmentInfo);
  geometry.instanceCount = 0;
  return geometry;
}

function materialKey(material) {
  if (!material || Array.isArray(material)) return null;
  return material.uuid || material.name || null;
}

function mergeTemplateParts(parts) {
  const groups = new Map();
  const passthrough = [];
  for (const part of parts) {
    const key = materialKey(part.material);
    if (!key) {
      passthrough.push(part);
      continue;
    }
    if (!groups.has(key)) {
      groups.set(key, {
        material: part.material,
        geometries: [],
        names: [],
      });
    }
    const group = groups.get(key);
    group.geometries.push(part.geometry);
    group.names.push(part.name);
  }

  const mergedParts = [...passthrough];
  for (const group of groups.values()) {
    if (group.geometries.length === 1) {
      mergedParts.push({
        geometry: group.geometries[0],
        material: group.material,
        name: group.names[0],
      });
      continue;
    }
    try {
      let geometry = mergeGeometries(group.geometries, false);
      if (geometry) {
        geometry = optimizeStaticGeometry(geometry);
        geometry.computeBoundingBox();
        geometry.computeBoundingSphere();
        group.geometries.forEach((sourceGeometry) => sourceGeometry.dispose?.());
        mergedParts.push({
          geometry,
          material: group.material,
          name: group.names.join("+"),
        });
      } else {
        group.geometries.forEach((geometry, index) => {
          mergedParts.push({
            geometry,
            material: group.material,
            name: group.names[index],
          });
        });
      }
    } catch (error) {
      console.warn("[VehicleModelLayer] merge original GLB parts failed, rendering unmerged parts:", error);
      group.geometries.forEach((geometry, index) => {
        mergedParts.push({
          geometry,
          material: group.material,
          name: group.names[index],
        });
      });
    }
  }
  return mergedParts;
}

function createVehicleTemplate(mode, gltf, materialSet) {
  const config = VEHICLE_MODEL_CONFIG[mode] || VEHICLE_MODEL_CONFIG.car;
  const root = gltf?.scene || gltf?.scenes?.[0];
  if (!root) {
    throw new Error(`GLB scene is empty: ${mode}`);
  }

  root.updateMatrixWorld(true);
  let parts = [];
  root.traverse((object) => {
    if (!object?.isMesh || !object.geometry || !object.material) return;
    const geometry = object.geometry.clone();
    geometry.applyMatrix4(object.matrixWorld);
    geometry.rotateX(Math.PI / 2);
    if (Number.isFinite(config.bakeYaw) && Math.abs(config.bakeYaw) > 0.0001) {
      geometry.rotateZ(config.bakeYaw);
    }
    if (!geometry.getAttribute("normal")) {
      geometry.computeVertexNormals();
    }
    const optimizedGeometry = optimizeStaticGeometry(geometry);
    optimizedGeometry.computeBoundingBox();
    optimizedGeometry.computeBoundingSphere();
    collectMaterials(object.material, materialSet);
    parts.push({
      geometry: optimizedGeometry,
      material: object.material,
      name: object.name || `${mode}-mesh-${parts.length}`,
    });
  });

  if (!parts.length) {
    throw new Error(`GLB has no renderable mesh: ${mode}`);
  }

  let box = computePartsBox(parts);
  let size = new THREE.Vector3();
  box?.getSize(size);
  if (size.y > size.x * 1.08) {
    for (const part of parts) {
      part.geometry.rotateZ(-Math.PI / 2);
    }
    box = computePartsBox(parts);
    size = new THREE.Vector3();
    box?.getSize(size);
  }

  const center = new THREE.Vector3();
  box?.getCenter(center);
  const targetLength = Math.max(1, Number(config.dimensions?.[0]) || 6)
    * Math.max(0.1, Number(config.displayScale) || 1);
  const horizontalSize = Math.max(size.x, size.y, 0.001);
  const fitScale = targetLength / horizontalSize;

  for (const part of parts) {
    part.geometry.translate(-center.x, -center.y, -(box?.min.z || 0));
    part.geometry.scale(fitScale, fitScale, fitScale);
    part.geometry.computeBoundingBox();
    part.geometry.computeBoundingSphere();
  }
  parts = mergeVertexColoredParts(parts, materialSet, mode);
  parts = mergeTemplateParts(parts);

  return {
    mode,
    url: config.url,
    parts,
    sourceBox: box,
    fitScale,
    yawOffset: Number(config.yawOffset) || 0,
  };
}

export class VehicleModelLayer {
  constructor({ id, modes = Object.keys(VEHICLE_MODEL_CONFIG), onReady = null } = {}) {
    this.id = id || "vehicle-model-layer";
    this.type = "custom";
    this.renderingMode = "3d";
    this.modes = modes;
    this.onReady = onReady;
    this.visible = true;
    this.map = null;
    this.mapWrapper = null;
    this.renderer = null;
    this.camera = new THREE.Camera();
    this.scene = new THREE.Scene();
    this.modelMatrix = new THREE.Matrix4();
    this.mapMatrix = new THREE.Matrix4();
    this.tmpMatrix = new THREE.Matrix4();
    this.tmpPosition = new THREE.Vector3();
    this.tmpQuaternion = new THREE.Quaternion();
    this.tmpScale = new THREE.Vector3();
    this.zAxis = new THREE.Vector3(0, 0, 1);
    this.origin = [0, 0];
    this.originReady = false;
    this.vehicleScaleRatio = 1;
    this.vehicleScale = DEFAULT_MODEL_WORLD_SCALE;
    this.renderVehicleScale = DEFAULT_MODEL_WORLD_SCALE;
    this.trajectoryTime = 0;
    this.vehicles = [];
    this.vehicleFrame = null;
    // 当前帧的内容版本号（帧对象被原地复用重写时由上游递增），用于 setVehicles 快速路径判定。
    this.vehicleFrameRev = null;
    this.templates = new Map();
    this.meshGroups = new Map();
    this.materials = new Set();
    this.ready = false;
    this.loadingPromise = null;
    this.lastVisibleCount = 0;
    this.lastTotalCount = 0;
    this.lastVisibleFirst = null;
    this.lastModeCounts = {};
    this.lastDebugPublishAt = 0;
    this.lastRenderDebugAt = 0;
    this.displayState = new Map();
    // 平滑状态存活标记：每轮 updateInstances 递增，状态对象原地写入 seen，
    // 替代原先每帧向 Set 写入 key 的做法（零分配）。
    this.displayStateStamp = 0;
    // 最近一次完整重写时的平滑开关状态：zoom 跨越 SMOOTH_MIN_ZOOM 时禁用快速路径，保证阈值切换行为不变。
    this.lastUseSmoothing = false;
    this.lastInstanceUpdateAt = 0;
  }

  setMapWrapper(mapWrapper) {
    this.mapWrapper = mapWrapper;
  }

  onAdd(map, gl) {
    this.map = map;
    this.renderer = new THREE.WebGLRenderer({
      canvas: map.getCanvas(),
      context: gl,
      antialias: true,
      alpha: true,
    });
    this.renderer.autoClear = false;
    this.renderer.autoClearColor = false;
    this.renderer.autoClearDepth = false;
    this.renderer.autoClearStencil = false;
    this.renderer.shadowMap.enabled = false;
    this.renderer.setPixelRatio(rendererPixelRatio());
    if ("outputColorSpace" in this.renderer && THREE.SRGBColorSpace) {
      this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    }

    this.scene.add(new THREE.HemisphereLight(0xffffff, 0x8b95a5, 1.7));
    const keyLight = new THREE.DirectionalLight(0xffffff, 1.8);
    keyLight.position.set(120, -80, 180);
    this.scene.add(keyLight);
    const fillLight = new THREE.DirectionalLight(0xffffff, 0.8);
    fillLight.position.set(-160, 90, 120);
    this.scene.add(fillLight);

    this.ensureModels();
  }

  render(gl, options) {
    if (!this.visible || !this.ready) return;
    const renderStartedAt = typeof performance !== "undefined" ? performance.now() : 0;
    const matrix = options?.defaultProjectionData?.mainMatrix || options?.modelViewProjectionMatrix || options;
    if (!matrix) return;
    this.camera.projectionMatrix.copy(this.mapMatrix.fromArray(matrix)).multiply(this.modelMatrix);
    this.renderer.autoClear = false;
    this.renderer.autoClearColor = false;
    this.renderer.autoClearDepth = false;
    this.renderer.autoClearStencil = false;
    this.renderer.resetState();
    // 保留 MapLibre 已建立的深度缓冲。建筑层即便因外部代码调整了图层顺序，
    // 也能正确遮挡位于其后的道路、点和车辆；清空 depth 会让车辆永远浮在建筑前方。
    this.renderer.render(this.scene, this.camera);
    if (DEBUG_CHANNEL_ENABLED && renderStartedAt && typeof document !== "undefined") {
      const now = performance.now();
      if (now - this.lastRenderDebugAt > DEBUG_PUBLISH_INTERVAL_MS) {
        this.lastRenderDebugAt = now;
        document.documentElement.dataset.gjVehicleRenderMs = String(Math.round((now - renderStartedAt) * 100) / 100);
      }
    }
  }

  async ensureModels() {
    if (this.ready) return true;
    if (!this.loadingPromise) {
      this.loadingPromise = Promise.all(
        this.modes.map(async (mode) => {
          const config = VEHICLE_MODEL_CONFIG[mode] || VEHICLE_MODEL_CONFIG.car;
          try {
            const gltf = await loadGltf(config.url);
            this.templates.set(mode, createVehicleTemplate(mode, gltf, this.materials));
          } catch (error) {
            console.error(`[VehicleModelLayer] failed to load original GLB for ${mode}:`, error);
            this.templates.delete(mode);
          }
        }),
      )
        .then(() => {
          this.ready = true;
          this.updateInstances();
          this.onReady?.();
          this.map?.triggerRepaint?.();
          return true;
        })
        .finally(() => {
          this.loadingPromise = null;
        });
    }
    return this.loadingPromise;
  }

  setVisible(visible) {
    this.visible = visible !== false;
    for (const group of this.meshGroups.values()) {
      for (const mesh of group.meshes) {
        mesh.visible = this.visible;
      }
    }
    this.map?.triggerRepaint?.();
  }

  setVehicleScale(scale) {
    this.vehicleScaleRatio = Math.max(0.35, Math.min(2.5, Number(scale) || 1));
    const nextScale = this.vehicleScaleRatio * DEFAULT_MODEL_WORLD_SCALE;
    const scaleChanged = Math.abs(nextScale - this.vehicleScale) >= 0.001;
    this.vehicleScale = nextScale;
    this.updateRenderScaleUniforms(true);
    if (!scaleChanged) return;
    this.updateInstances();
  }

  currentRenderVehicleScale() {
    return this.vehicleScale * lowZoomScaleMultiplier(this.mapWrapper?.zoom);
  }

  updateRenderScaleUniforms(force = false) {
    const nextScale = this.currentRenderVehicleScale();
    if (!force && Math.abs(nextScale - this.renderVehicleScale) < 0.001) return false;
    this.renderVehicleScale = nextScale;
    for (const group of this.meshGroups.values()) {
      const useGpuTrajectory = group.userData?.mode === "segments";
      for (const mesh of group.meshes) {
        setFastMaterialUniforms(mesh.material, this.renderVehicleScale, {
          time: this.trajectoryTime,
          useGpuTrajectory,
        });
      }
    }
    return true;
  }

  setTrajectoryTime(seconds) {
    const nextTime = Math.max(0, Number(seconds) || 0);
    const scaleChanged = this.updateRenderScaleUniforms();
    if (Math.abs(nextTime - this.trajectoryTime) < 0.0001 && !scaleChanged) return;
    this.trajectoryTime = nextTime;
    for (const group of this.meshGroups.values()) {
      const useGpuTrajectory = group.userData?.mode === "segments";
      for (const mesh of group.meshes) {
        setFastMaterialUniforms(mesh.material, this.renderVehicleScale, {
          time: this.trajectoryTime,
          useGpuTrajectory,
        });
      }
    }
    this.map?.triggerRepaint?.();
  }

  setVehicles(vehicles = []) {
    const isFrame = vehicles?.kind === "vehicle-frame" || vehicles?.kind === "vehicle-segment-frame";
    const renderScaleChanged = this.updateRenderScaleUniforms();
    // 快速路径：相机移动（平移/缩放/旋转/resize）会重复下发同一帧对象。
    // 若帧对象与内容版本号都未变化、且原点无需 rebase（chooseOrigin 返回 false），
    // 实例缓冲内容不会有任何差异，直接触发重绘即可，跳过 O(N) 的全量重写与 GPU 上传。
    // 播放推进/数据重载/可见性切换会产生新帧对象或递增 __contentRev；原点 rebase 时
    // chooseOrigin 返回 true（并已更新 modelMatrix），仍走下方完整重写。
    const zoomNow = Number(this.mapWrapper?.zoom);
    const useSmoothingNow = Number.isFinite(zoomNow) && zoomNow >= SMOOTH_MIN_ZOOM;
    if (
      isFrame &&
      this.ready &&
      this.originReady &&
      vehicles === this.vehicleFrame &&
      vehicles.__contentRev === this.vehicleFrameRev &&
      (vehicles.kind !== "vehicle-frame" || useSmoothingNow === this.lastUseSmoothing) &&
      !renderScaleChanged &&
      !this.chooseOrigin()
    ) {
      this.publishDebug();
      this.map?.triggerRepaint?.();
      return;
    }
    if (isFrame) {
      this.vehicleFrame = vehicles;
      this.vehicleFrameRev = vehicles.__contentRev;
      this.vehicles = [];
    } else {
      this.vehicleFrame = null;
      this.vehicleFrameRev = null;
      this.vehicles = Array.isArray(vehicles) ? vehicles : [];
    }
    if (this.activeTotal() <= 0) {
      this.displayState.clear();
      this.lastInstanceUpdateAt = 0;
    }
    this.lastModeCounts = {};
    this.updateInstances();
  }

  activeTotal() {
    if (this.vehicleFrame?.kind === "vehicle-frame" || this.vehicleFrame?.kind === "vehicle-segment-frame") {
      return Math.max(0, Number(this.vehicleFrame.count) || 0);
    }
    return this.vehicles.length;
  }

  currentModeCounts() {
    if (this.lastModeCounts && Object.keys(this.lastModeCounts).length) {
      return { ...this.lastModeCounts };
    }
    const counts = {};
    for (const mode of this.modes) {
      counts[mode] = 0;
    }
    if (this.vehicleFrame?.kind === "vehicle-frame") {
      const count = this.activeTotal();
      const modes = this.vehicleFrame.modes || [];
      for (let i = 0; i < count; i++) {
        const mode = MODE_INDEX_TO_KEY[Math.round(Number(modes[i]) || 0)] || "car";
        counts[mode] = (counts[mode] || 0) + 1;
      }
    } else if (this.vehicleFrame?.kind === "vehicle-segment-frame") {
      const count = this.activeTotal();
      const modes = this.vehicleFrame.modes || [];
      for (let i = 0; i < count; i++) {
        const mode = MODE_INDEX_TO_KEY[Math.round(Number(modes[i]) || 0)] || "car";
        counts[mode] = (counts[mode] || 0) + 1;
      }
    } else {
      for (const vehicle of this.vehicles) {
        if (vehicle?.mode) {
          counts[vehicle.mode] = (counts[vehicle.mode] || 0) + 1;
        }
      }
    }
    this.lastModeCounts = counts;
    return { ...counts };
  }

  publishDebug(force = false) {
    if (!DEBUG_CHANNEL_ENABLED) return;
    if (typeof document === "undefined") return;
    const now = typeof performance !== "undefined" ? performance.now() : Date.now();
    if (!force && now - this.lastDebugPublishAt < DEBUG_PUBLISH_INTERVAL_MS) {
      return;
    }
    this.lastDebugPublishAt = now;
    const counts = this.currentModeCounts();
    const total = Object.values(counts).reduce((sum, value) => sum + Number(value || 0), 0);
    if (document?.documentElement?.dataset) {
      document.documentElement.dataset.gjVehicleModelReady = this.ready ? "1" : "0";
      document.documentElement.dataset.gjVehicleModelVisible = this.visible ? "1" : "0";
      document.documentElement.dataset.gjVehicleModelTotal = String(this.lastTotalCount || total);
      document.documentElement.dataset.gjVehicleModelVisibleCount = String(this.lastVisibleCount || total);
      document.documentElement.dataset.gjVehicleModelCounts = JSON.stringify(counts);
      document.documentElement.dataset.gjVehicleModelFirst = JSON.stringify(this.firstVehiclePosition());
      document.documentElement.dataset.gjVehicleModelVisibleFirst = JSON.stringify(this.lastVisibleFirst || null);
    }
  }

  firstVehiclePosition() {
    const frame = this.vehicleFrame;
    if (frame?.kind === "vehicle-frame" && this.activeTotal() > 0) {
      return [Number(frame.xs?.[0]), Number(frame.ys?.[0])];
    }
    if (frame?.kind === "vehicle-segment-frame" && this.activeTotal() > 0) {
      const [originX = 0, originY = 0] = frame.origin || [];
      const startX = Number(frame.startXs?.[0]);
      const startY = Number(frame.startYs?.[0]);
      if (Number.isFinite(startX) && Number.isFinite(startY)) {
        return [Number(originX) + startX, Number(originY) + startY];
      }
    }
    return this.vehicles[0]?.position || null;
  }

  originRebaseThreshold() {
    const zoom = Number(this.mapWrapper?.zoom);
    if (!Number.isFinite(zoom)) return ORIGIN_REBASE_DEFAULT_METERS;
    if (zoom >= 17) return 96;
    if (zoom >= 16) return 220;
    if (zoom >= 15) return 650;
    if (zoom >= 14) return 1800;
    if (zoom >= 13) return 6000;
    return ORIGIN_REBASE_DEFAULT_METERS;
  }

  chooseOrigin() {
    const center = this.mapWrapper?.center;
    const source = center?.length >= 2
      ? center
      : this.vehicles.find((vehicle) => vehicle?.webMercator)?.webMercator;
    if (!source?.length) return false;

    const x = Number(source[0]);
    const y = Number(source[1]);
    if (![x, y].every(Number.isFinite)) return false;
    const distance = Math.hypot(x - this.origin[0], y - this.origin[1]);
    if (this.originReady && distance < this.originRebaseThreshold()) return false;

    this.origin = [x, y];
    this.originReady = true;
    const [unitX, unitY] = webMercatorToMercatorUnit(x, y);
    const translate = new THREE.Matrix4().makeTranslation(unitX, unitY, 0);
    const scale = new THREE.Matrix4().makeScale(
      MERCATOR_UNIT_PER_WEB_MERCATOR_METER,
      -MERCATOR_UNIT_PER_WEB_MERCATOR_METER,
      MERCATOR_UNIT_PER_WEB_MERCATOR_METER,
    );
    this.modelMatrix.multiplyMatrices(translate, scale);
    return true;
  }

  // （原 modeVehicles/visibleBounds/isInVisibleBounds/isFrameIndexInVisibleBounds/shouldUseScreenCull/vehicleBuckets
  //  双重剔除体系从未被实例更新路径调用，属死代码，已删除；如需视口剔除应在每秒段帧重写时按视口过滤写入实例。）

  displayKeyForVehicle(mode, frame, frameIndex, vehicle) {
    if (frame) {
      const id = Number(frame.ids?.[frameIndex]);
      if (Number.isFinite(id)) return `${mode}:${Math.round(id)}`;
      return `${mode}:frame:${frameIndex}`;
    }
    return `${mode}:${vehicle?.key || vehicle?.id || vehicle?.vehicleIndex || vehicle?.webMercator?.join(":")}`;
  }

  smoothVehicleState(key, targetX, targetY, targetAngle, now, dt) {
    const zoom = Number(this.mapWrapper?.zoom);
    const previous = this.displayState.get(key);
    const tx = Number(targetX);
    const ty = Number(targetY);
    const tAngle = normalizeAngleDegrees(targetAngle);
    // 仅在新 key 首次出现时分配状态对象，后续每帧原地复用，避免每车每帧 new 对象 + Map 写入。
    let state = previous;
    if (!state) {
      state = { x: NaN, y: NaN, angle: 0, updatedAt: 0, seen: 0 };
      this.displayState.set(key, state);
    }
    state.seen = this.displayStateStamp;
    if (
      !previous ||
      !Number.isFinite(state.x) ||
      !Number.isFinite(state.y) ||
      !Number.isFinite(zoom) ||
      zoom < SMOOTH_MIN_ZOOM
    ) {
      state.x = tx;
      state.y = ty;
      state.angle = tAngle;
      state.updatedAt = now;
      return state;
    }

    const distance = Math.hypot(tx - state.x, ty - state.y);
    if (!Number.isFinite(distance) || distance > SMOOTH_SNAP_METERS || dt <= 0 || dt > 180) {
      state.x = tx;
      state.y = ty;
      state.angle = tAngle;
      state.updatedAt = now;
      return state;
    }

    const zoomFactor = clamp((zoom - SMOOTH_MIN_ZOOM) / 3.4, 0, 1);
    const timeConstant = 44 + zoomFactor * 42;
    let alpha = 1 - Math.exp(-dt / timeConstant);
    if (distance > 50) {
      alpha = Math.max(alpha, Math.min(0.9, distance / 120));
    }
    alpha = clamp(alpha, 0.18, 0.92);

    state.x = state.x + (tx - state.x) * alpha;
    state.y = state.y + (ty - state.y) * alpha;
    state.angle = lerpAngleDegrees(state.angle, tAngle, Math.min(1, alpha * 1.25));
    state.updatedAt = now;
    return state;
  }

  pruneDisplayState() {
    if (!this.displayState.size) return;
    for (const [key, state] of this.displayState) {
      if (state.seen !== this.displayStateStamp) {
        this.displayState.delete(key);
      }
    }
  }

  visibleWebBounds() {
    const bounds = this.mapWrapper?.getWindowRangeAndWebMercator?.();
    if (!bounds) return null;
    const padding = Math.max(
      VEHICLE_CULL_PADDING_METERS,
      (Number(this.mapWrapper?.cameraHeight) || 0) * 0.01,
      this.renderVehicleScale * 24,
    );
    return {
      minX: Number(bounds.minX) - padding,
      minY: Number(bounds.minY) - padding,
      maxX: Number(bounds.maxX) + padding,
      maxY: Number(bounds.maxY) + padding,
    };
  }

  pointInVisibleBounds(x, y, bounds) {
    if (!bounds) return true;
    return x >= bounds.minX && x <= bounds.maxX && y >= bounds.minY && y <= bounds.maxY;
  }

  segmentInVisibleBounds(x1, y1, x2, y2, bounds) {
    if (!bounds) return true;
    const minX = Math.min(x1, x2);
    const maxX = Math.max(x1, x2);
    const minY = Math.min(y1, y2);
    const maxY = Math.max(y1, y2);
    return maxX >= bounds.minX && minX <= bounds.maxX && maxY >= bounds.minY && minY <= bounds.maxY;
  }

  disposeMeshGroup(group) {
    if (!group) return;
    for (const mesh of group.meshes || []) {
      mesh.removeFromParent();
      mesh.geometry?.dispose?.();
      disposeMaterialInstance(mesh.material);
    }
  }

  ensureMeshGroup(mode, count) {
    const current = this.meshGroups.get(mode);
    if (current && current.userData.capacity >= count) {
      return current;
    }
    if (current) {
      this.disposeMeshGroup(current);
    }

    const template = this.templates.get(mode);
    if (!template?.parts?.length) return null;

    const capacity = nextCapacity(count);
    const transformBuffer = new Float32Array(capacity * 4);
    const instanceTransform = new THREE.InstancedBufferAttribute(transformBuffer, 4);
    instanceTransform.setUsage(THREE.StreamDrawUsage || THREE.DynamicDrawUsage);
    const segmentXYBuffer = new Float32Array(capacity * 4);
    const segmentInfoBuffer = new Float32Array(capacity * 4);
    const instanceSegmentXY = new THREE.InstancedBufferAttribute(segmentXYBuffer, 4);
    const instanceSegmentInfo = new THREE.InstancedBufferAttribute(segmentInfoBuffer, 4);
    instanceSegmentXY.setUsage(THREE.StreamDrawUsage || THREE.DynamicDrawUsage);
    instanceSegmentInfo.setUsage(THREE.StreamDrawUsage || THREE.DynamicDrawUsage);
    const meshes = template.parts.map((part, index) => {
      const geometry = createInstancedGeometry(part.geometry, instanceTransform, instanceSegmentXY, instanceSegmentInfo);
      const material = createFastInstancedMaterial(part.material);
      setFastMaterialUniforms(material, this.renderVehicleScale, { time: this.trajectoryTime });
      const mesh = new THREE.Mesh(geometry, material);
      mesh.name = `${this.id}-${mode}-${index}-${part.name}`;
      mesh.frustumCulled = false;
      mesh.matrixAutoUpdate = false;
      mesh.visible = this.visible;
      this.scene.add(mesh);
      return mesh;
    });
    const group = {
      mode,
      meshes,
      instanceTransform,
      transformBuffer,
      instanceSegmentXY,
      instanceSegmentInfo,
      segmentXYBuffer,
      segmentInfoBuffer,
      userData: { capacity, mode: "transforms" },
    };
    this.meshGroups.set(mode, group);
    return group;
  }

  updateSegmentInstances(frame) {
    this.publishDebug();
    if (!this.ready) return;
    this.chooseOrigin();
    if (!this.originReady) return;

    const frameCount = this.activeTotal();
    const frameModes = frame?.modes || [];
    const modeCounts = Object.fromEntries(this.modes.map((mode) => [mode, 0]));
    const [frameOriginX = 0, frameOriginY = 0] = frame.origin || [];
    const bounds = this.visibleWebBounds();
    this.lastVisibleFirst = null;
    let total = 0;
    let visible = 0;

    for (let index = 0; index < frameCount; index++) {
      const mode = MODE_INDEX_TO_KEY[Math.round(Number(frameModes[index]) || 0)] || "car";
      const startX = Number(frame.startXs?.[index]);
      const startY = Number(frame.startYs?.[index]);
      const endX = Number(frame.endXs?.[index]);
      const endY = Number(frame.endYs?.[index]);
      const startTime = Number(frame.startTimes?.[index]);
      const endTime = Number(frame.endTimes?.[index]);
      if (
        !(mode in modeCounts) ||
        ![startX, startY, endX, endY, startTime, endTime].every(Number.isFinite) ||
        endTime <= startTime
      ) {
        continue;
      }
      total += 1;
      const worldStartX = Number(frameOriginX) + startX;
      const worldStartY = Number(frameOriginY) + startY;
      const worldEndX = Number(frameOriginX) + endX;
      const worldEndY = Number(frameOriginY) + endY;
      if (!this.segmentInVisibleBounds(worldStartX, worldStartY, worldEndX, worldEndY, bounds)) {
        continue;
      }
      modeCounts[mode] += 1;
      visible += 1;
      if (!this.lastVisibleFirst) {
        const duration = Math.max(endTime - startTime, 0.001);
        const ratio = clamp((this.trajectoryTime - startTime) / duration, 0, 1);
        this.lastVisibleFirst = {
          mode,
          webMercator: [
            worldStartX + (endX - startX) * ratio,
            worldStartY + (endY - startY) * ratio,
          ],
          position: null,
          screen: null,
          angle: Math.atan2(endY - startY, endX - startX) / DEG_TO_RAD,
        };
      }
    }

    this.lastTotalCount = total;
    this.lastVisibleCount = visible;
    this.lastModeCounts = { ...modeCounts };

    const groups = new Map();
    const writeOffsets = Object.fromEntries(this.modes.map((mode) => [mode, 0]));
    for (const mode of this.modes) {
      const count = modeCounts[mode] || 0;
      const existing = this.meshGroups.get(mode);
      const group = count > 0 ? this.ensureMeshGroup(mode, count) : existing;
      if (!group) continue;
      group.userData.mode = "segments";
      groups.set(mode, group);
      for (const mesh of group.meshes) {
        mesh.geometry.instanceCount = count;
        setFastMaterialUniforms(mesh.material, this.renderVehicleScale, {
          time: this.trajectoryTime,
          useGpuTrajectory: true,
        });
      }
    }

    for (let index = 0; index < frameCount; index++) {
      const mode = MODE_INDEX_TO_KEY[Math.round(Number(frameModes[index]) || 0)] || "car";
      const group = groups.get(mode);
      if (!group) continue;
      const startX = Number(frame.startXs?.[index]);
      const startY = Number(frame.startYs?.[index]);
      const endX = Number(frame.endXs?.[index]);
      const endY = Number(frame.endYs?.[index]);
      const startTime = Number(frame.startTimes?.[index]);
      const endTime = Number(frame.endTimes?.[index]);
      if (
        ![startX, startY, endX, endY, startTime, endTime].every(Number.isFinite) ||
        endTime <= startTime
      ) {
        continue;
      }
      const worldStartX = Number(frameOriginX) + startX;
      const worldStartY = Number(frameOriginY) + startY;
      const worldEndX = Number(frameOriginX) + endX;
      const worldEndY = Number(frameOriginY) + endY;
      if (!this.segmentInVisibleBounds(worldStartX, worldStartY, worldEndX, worldEndY, bounds)) {
        continue;
      }
      const writeIndex = writeOffsets[mode] || 0;
      const yawOffset = this.templates.get(mode)?.yawOffset || 0;
      writeInstanceSegment(
        group.segmentXYBuffer,
        group.segmentInfoBuffer,
        writeIndex,
        worldStartX - this.origin[0],
        worldStartY - this.origin[1],
        worldEndX - this.origin[0],
        worldEndY - this.origin[1],
        startTime,
        endTime,
        Math.atan2(endY - startY, endX - startX) + yawOffset,
      );
      writeOffsets[mode] = writeIndex + 1;
    }

    for (const mode of this.modes) {
      const group = groups.get(mode);
      if (!group) continue;
      const count = writeOffsets[mode] || 0;
      for (const mesh of group.meshes) {
        mesh.geometry.instanceCount = count;
      }
      if (count <= 0) continue;
      const updateCount = count * 4;
      for (const attribute of [group.instanceSegmentXY, group.instanceSegmentInfo]) {
        if (typeof attribute.clearUpdateRanges === "function") {
          attribute.clearUpdateRanges();
        }
        if (typeof attribute.addUpdateRange === "function") {
          attribute.addUpdateRange(0, updateCount);
        } else if (attribute.updateRange) {
          attribute.updateRange.offset = 0;
          attribute.updateRange.count = updateCount;
        }
        attribute.needsUpdate = true;
      }
    }

    this.map?.triggerRepaint?.();
    this.publishDebug(true);
  }

  updateInstances() {
    this.publishDebug();
    if (!this.ready) return;
    this.chooseOrigin();
    if (!this.originReady) return;

    const segmentFrame = this.vehicleFrame?.kind === "vehicle-segment-frame" ? this.vehicleFrame : null;
    if (segmentFrame) {
      this.updateSegmentInstances(segmentFrame);
      return;
    }

    const frame = this.vehicleFrame?.kind === "vehicle-frame" ? this.vehicleFrame : null;
    const frameCount = frame ? this.activeTotal() : 0;
    const frameModes = frame?.modes || [];
    const modeCounts = Object.fromEntries(this.modes.map((mode) => [mode, 0]));
    const bounds = this.visibleWebBounds();
    this.lastVisibleFirst = null;
    let total = 0;
    let visible = 0;

    if (frame) {
      for (let index = 0; index < frameCount; index++) {
        const webX = Number(frame.xs?.[index]);
        const webY = Number(frame.ys?.[index]);
        if (!Number.isFinite(webX) || !Number.isFinite(webY)) continue;
        const mode = MODE_INDEX_TO_KEY[Math.round(Number(frameModes[index]) || 0)] || "car";
        if (!(mode in modeCounts)) continue;
        total += 1;
        if (!this.pointInVisibleBounds(webX, webY, bounds)) continue;
        modeCounts[mode] += 1;
        visible += 1;
        if (!this.lastVisibleFirst) {
          this.lastVisibleFirst = {
            mode,
            webMercator: [webX, webY],
            position: null,
            screen: null,
            angle: Number(frame.angles?.[index]) || 0,
          };
        }
      }
    } else {
      for (const vehicle of this.vehicles) {
        const mode = vehicle?.mode;
        const webX = Number(vehicle?.webMercator?.[0]);
        const webY = Number(vehicle?.webMercator?.[1]);
        if (!(mode in modeCounts) || !Number.isFinite(webX) || !Number.isFinite(webY)) continue;
        total += 1;
        if (!this.pointInVisibleBounds(webX, webY, bounds)) continue;
        modeCounts[mode] += 1;
        visible += 1;
        if (!this.lastVisibleFirst) {
          const screen = vehicle.position ? this.map?.project?.(vehicle.position) : null;
          this.lastVisibleFirst = {
            mode,
            webMercator: vehicle.webMercator,
            position: vehicle.position,
            screen: screen ? [screen.x, screen.y] : null,
            angle: vehicle.angle,
          };
        }
      }
    }

    this.lastTotalCount = total;
    this.lastVisibleCount = visible;
    this.lastModeCounts = { ...modeCounts };

    const now = typeof performance !== "undefined" ? performance.now() : Date.now();
    const dt = this.lastInstanceUpdateAt ? now - this.lastInstanceUpdateAt : 16;
    this.lastInstanceUpdateAt = now;
    const zoom = Number(this.mapWrapper?.zoom);
    const useSmoothing = Number.isFinite(zoom) && zoom >= SMOOTH_MIN_ZOOM;
    this.lastUseSmoothing = useSmoothing;
    if (useSmoothing) {
      this.displayStateStamp += 1;
    } else if (this.displayState.size) {
      this.displayState.clear();
    }

    const groups = new Map();
    const writeOffsets = Object.fromEntries(this.modes.map((mode) => [mode, 0]));
    for (const mode of this.modes) {
      const count = modeCounts[mode] || 0;
      const existing = this.meshGroups.get(mode);
      const group = count > 0 ? this.ensureMeshGroup(mode, count) : existing;
      if (!group) continue;
      group.userData.mode = "transforms";
      groups.set(mode, group);
      for (const mesh of group.meshes) {
        mesh.geometry.instanceCount = count;
        setFastMaterialUniforms(mesh.material, this.renderVehicleScale, {
          time: this.trajectoryTime,
          useGpuTrajectory: false,
        });
      }
    }

    const writeVehicle = (mode, frameIndex, vehicle) => {
      const group = groups.get(mode);
      if (!group) return;
      const writeIndex = writeOffsets[mode] || 0;
      const webX = frame ? Number(frame.xs[frameIndex]) : Number(vehicle.webMercator[0]);
      const webY = frame ? Number(frame.ys[frameIndex]) : Number(vehicle.webMercator[1]);
      if (!Number.isFinite(webX) || !Number.isFinite(webY)) return;

      const rawAngle = frame ? frame.angles?.[frameIndex] : vehicle.angle;
      let displayX = webX;
      let displayY = webY;
      let displayAngle = Number(rawAngle) || 0;
      if (useSmoothing) {
        const key = this.displayKeyForVehicle(mode, frame, frameIndex, vehicle);
        const display = this.smoothVehicleState(key, webX, webY, rawAngle, now, dt);
        displayX = display.x;
        displayY = display.y;
        displayAngle = display.angle;
      }

      const yawOffset = this.templates.get(mode)?.yawOffset || 0;
      writeInstanceTransform(
        group.transformBuffer,
        writeIndex * 4,
        displayX - this.origin[0],
        displayY - this.origin[1],
        displayAngle * DEG_TO_RAD + yawOffset,
      );
      writeOffsets[mode] = writeIndex + 1;
    };

    if (frame) {
      for (let index = 0; index < frameCount; index++) {
        const mode = MODE_INDEX_TO_KEY[Math.round(Number(frameModes[index]) || 0)] || "car";
        if (!(mode in modeCounts)) continue;
        const webX = Number(frame.xs?.[index]);
        const webY = Number(frame.ys?.[index]);
        if (!Number.isFinite(webX) || !Number.isFinite(webY) || !this.pointInVisibleBounds(webX, webY, bounds)) continue;
        writeVehicle(mode, index, null);
      }
    } else {
      for (const vehicle of this.vehicles) {
        const mode = vehicle?.mode;
        if (!(mode in modeCounts) || !vehicle.webMercator?.length) continue;
        const webX = Number(vehicle.webMercator[0]);
        const webY = Number(vehicle.webMercator[1]);
        if (!Number.isFinite(webX) || !Number.isFinite(webY) || !this.pointInVisibleBounds(webX, webY, bounds)) continue;
        writeVehicle(mode, -1, vehicle);
      }
    }

    for (const mode of this.modes) {
      const group = groups.get(mode);
      if (!group) continue;
      const count = writeOffsets[mode] || 0;
      for (const mesh of group.meshes) {
        mesh.geometry.instanceCount = count;
      }
      if (count <= 0) continue;
      const updateCount = count * 4;
      if (typeof group.instanceTransform.clearUpdateRanges === "function") {
        group.instanceTransform.clearUpdateRanges();
      }
      if (typeof group.instanceTransform.addUpdateRange === "function") {
        group.instanceTransform.addUpdateRange(0, updateCount);
      } else if (group.instanceTransform.updateRange) {
        group.instanceTransform.updateRange.offset = 0;
        group.instanceTransform.updateRange.count = updateCount;
      }
      group.instanceTransform.needsUpdate = true;
    }

    if (useSmoothing) {
      this.pruneDisplayState();
    }
    this.map?.triggerRepaint?.();
    this.publishDebug(true);
  }

  dispose() {
    for (const group of this.meshGroups.values()) {
      this.disposeMeshGroup(group);
    }
    for (const template of this.templates.values()) {
      for (const part of template.parts || []) {
        part.geometry.dispose?.();
      }
    }
    for (const material of this.materials) {
      disposeMaterial(material);
    }
    this.meshGroups.clear();
    this.templates.clear();
    this.materials.clear();
    this.renderer?.dispose?.();
    this.renderer = null;
  }

  onRemove() {
    this.dispose();
  }
}
