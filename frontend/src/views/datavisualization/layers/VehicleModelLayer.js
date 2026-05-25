import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { mergeGeometries } from "three/addons/utils/BufferGeometryUtils.js";

const EARTH_RADIUS = 6378137.0;
const WEB_MERCATOR_HALF_WORLD = Math.PI * EARTH_RADIUS;
const WEB_MERCATOR_WORLD_SIZE = WEB_MERCATOR_HALF_WORLD * 2;
const MERCATOR_UNIT_PER_WEB_MERCATOR_METER = 1 / WEB_MERCATOR_WORLD_SIZE;
const MIN_INSTANCE_CAPACITY = 16;
const DEFAULT_MODEL_WORLD_SCALE = 4;
const VEHICLE_CULL_PADDING_METERS = 220;
const VEHICLE_CULL_PADDING_PIXELS = 48;
const DEBUG_PUBLISH_INTERVAL_MS = 250;
const ORIGIN_REBASE_DEFAULT_METERS = 50000;
const SMOOTH_MIN_ZOOM = 15.3;
const SMOOTH_SNAP_METERS = 900;
const MODE_INDEX_TO_KEY = ["bus", "subway", "car"];

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
      const geometry = mergeGeometries(group.geometries, false);
      if (geometry) {
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
    geometry.computeBoundingBox();
    geometry.computeBoundingSphere();
    collectMaterials(object.material, materialSet);
    parts.push({
      geometry,
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
    this.vehicleScale = DEFAULT_MODEL_WORLD_SCALE;
    this.vehicles = [];
    this.vehicleFrame = null;
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
    this.displayState = new Map();
    this.displayStateSeen = new Set();
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
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
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
    const matrix = options?.defaultProjectionData?.mainMatrix || options?.modelViewProjectionMatrix || options;
    if (!matrix) return;
    this.camera.projectionMatrix.copy(this.mapMatrix.fromArray(matrix)).multiply(this.modelMatrix);
    this.renderer.autoClear = false;
    this.renderer.autoClearColor = false;
    this.renderer.autoClearDepth = false;
    this.renderer.autoClearStencil = false;
    this.renderer.resetState();
    gl.clear(gl.DEPTH_BUFFER_BIT);
    this.renderer.render(this.scene, this.camera);
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
    const nextScale = Math.max(0.35, Math.min(2.5, Number(scale) || 1)) * DEFAULT_MODEL_WORLD_SCALE;
    if (Math.abs(nextScale - this.vehicleScale) < 0.001) return;
    this.vehicleScale = nextScale;
    this.updateInstances();
  }

  setVehicles(vehicles = []) {
    if (vehicles?.kind === "vehicle-frame") {
      this.vehicleFrame = vehicles;
      this.vehicles = [];
    } else {
      this.vehicleFrame = null;
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
    if (this.vehicleFrame?.kind === "vehicle-frame") {
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

  modeVehicles(mode) {
    return this.vehicles.filter((vehicle) => vehicle?.mode === mode && vehicle.webMercator?.length >= 2);
  }

  visibleBounds() {
    const bounds = this.mapWrapper?.getWindowRangeAndWebMercator?.();
    const padding = Math.max(VEHICLE_CULL_PADDING_METERS, (this.mapWrapper?.cameraHeight || 0) * 0.004);
    const web = bounds
      ? {
          minX: bounds.minX - padding,
          minY: bounds.minY - padding,
          maxX: bounds.maxX + padding,
          maxY: bounds.maxY + padding,
        }
      : null;
    const canvas = this.map?.getCanvas?.();
    const pixelRatio = Math.max(1, window.devicePixelRatio || 1);
    const width = canvas?.clientWidth || (canvas?.width ? canvas.width / pixelRatio : 0);
    const height = canvas?.clientHeight || (canvas?.height ? canvas.height / pixelRatio : 0);
    const screen = width > 0 && height > 0
      ? {
          minX: -VEHICLE_CULL_PADDING_PIXELS,
          minY: -VEHICLE_CULL_PADDING_PIXELS,
          maxX: width + VEHICLE_CULL_PADDING_PIXELS,
          maxY: height + VEHICLE_CULL_PADDING_PIXELS,
        }
      : null;
    if (!web && !screen) return null;
    return { web, screen };
  }

  isInVisibleBounds(vehicle, bounds) {
    if (!bounds) return true;
    const web = bounds.web || bounds;
    const x = Number(vehicle.webMercator?.[0]);
    const y = Number(vehicle.webMercator?.[1]);
    if (web && Number.isFinite(x) && Number.isFinite(y)) {
      const inWebBounds = x >= web.minX
        && x <= web.maxX
        && y >= web.minY
        && y <= web.maxY;
      if (!inWebBounds || !this.shouldUseScreenCull(bounds)) {
        return inWebBounds;
      }
    }
    if (bounds.screen && vehicle.position && this.map?.project) {
      try {
        const point = this.map.project(vehicle.position);
        if (Number.isFinite(point?.x) && Number.isFinite(point?.y)) {
          return point.x >= bounds.screen.minX
            && point.x <= bounds.screen.maxX
            && point.y >= bounds.screen.minY
            && point.y <= bounds.screen.maxY;
        }
      } catch {
        // Fall back to the cached web-mercator bounds if map projection is unavailable mid-update.
      }
    }
    return true;
  }

  isFrameIndexInVisibleBounds(frame, index, bounds) {
    if (!bounds?.web) return true;
    const x = Number(frame.xs?.[index]);
    const y = Number(frame.ys?.[index]);
    const web = bounds.web;
    const inWebBounds = Number.isFinite(x)
      && Number.isFinite(y)
      && x >= web.minX
      && x <= web.maxX
      && y >= web.minY
      && y <= web.maxY;
    if (!inWebBounds || !this.shouldUseScreenCull(bounds)) {
      return inWebBounds;
    }
    try {
      const point = this.map?.project?.(webMercatorToLngLat(x, y));
      if (Number.isFinite(point?.x) && Number.isFinite(point?.y)) {
        return point.x >= bounds.screen.minX
          && point.x <= bounds.screen.maxX
          && point.y >= bounds.screen.minY
          && point.y <= bounds.screen.maxY;
      }
    } catch {
      return inWebBounds;
    }
    return inWebBounds;
  }

  shouldUseScreenCull(bounds) {
    if (!bounds?.screen || !this.map?.project) return false;
    return Boolean(this.mapWrapper?.enableRotate)
      || Number(this.mapWrapper?.pitch) < 89.5
      || Math.abs(Number(this.mapWrapper?.rotation) || 0) > 0.01;
  }

  vehicleBuckets() {
    const buckets = new Map(this.modes.map((mode) => [mode, []]));
    const counts = new Map(this.modes.map((mode) => [mode, 0]));
    const bounds = this.visibleBounds();
    let total = 0;
    let visible = 0;
    this.lastVisibleFirst = null;
    if (this.vehicleFrame?.kind === "vehicle-frame") {
      const frame = this.vehicleFrame;
      const count = this.activeTotal();
      const modes = frame.modes || [];
      for (let index = 0; index < count; index++) {
        const mode = MODE_INDEX_TO_KEY[Math.round(Number(modes[index]) || 0)] || "car";
        if (!buckets.has(mode)) continue;
        counts.set(mode, (counts.get(mode) || 0) + 1);
        total += 1;
        if (!this.isFrameIndexInVisibleBounds(frame, index, bounds)) continue;
        buckets.get(mode).push(index);
        if (!this.lastVisibleFirst) {
          this.lastVisibleFirst = {
            mode,
            webMercator: [Number(frame.xs[index]), Number(frame.ys[index])],
            position: null,
            screen: null,
            angle: Number(frame.angles?.[index]) || 0,
          };
        }
        visible += 1;
      }
      this.lastTotalCount = total;
      this.lastVisibleCount = visible;
      this.lastModeCounts = Object.fromEntries(counts);
      return { buckets, counts };
    }

    for (const vehicle of this.vehicles) {
      const mode = vehicle?.mode;
      if (!buckets.has(mode) || !vehicle.webMercator?.length) continue;
      counts.set(mode, (counts.get(mode) || 0) + 1);
      total += 1;
      if (!this.isInVisibleBounds(vehicle, bounds)) continue;
      buckets.get(mode).push(vehicle);
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
      visible += 1;
    }
    this.lastTotalCount = total;
    this.lastVisibleCount = visible;
    this.lastModeCounts = Object.fromEntries(counts);
    return { buckets, counts };
  }

  displayKeyForVehicle(mode, frame, frameIndex, vehicle) {
    if (frame) {
      const id = Number(frame.ids?.[frameIndex]);
      if (Number.isFinite(id)) return `${mode}:${Math.round(id)}`;
      return `${mode}:frame:${frameIndex}`;
    }
    return `${mode}:${vehicle?.key || vehicle?.id || vehicle?.vehicleIndex || vehicle?.webMercator?.join(":")}`;
  }

  smoothVehicleState(key, targetX, targetY, targetAngle, now, dt) {
    this.displayStateSeen.add(key);
    const zoom = Number(this.mapWrapper?.zoom);
    const previous = this.displayState.get(key);
    const target = {
      x: Number(targetX),
      y: Number(targetY),
      angle: normalizeAngleDegrees(targetAngle),
      updatedAt: now,
    };
    if (
      !previous ||
      !Number.isFinite(previous.x) ||
      !Number.isFinite(previous.y) ||
      !Number.isFinite(zoom) ||
      zoom < SMOOTH_MIN_ZOOM
    ) {
      this.displayState.set(key, target);
      return target;
    }

    const distance = Math.hypot(target.x - previous.x, target.y - previous.y);
    if (!Number.isFinite(distance) || distance > SMOOTH_SNAP_METERS || dt <= 0 || dt > 180) {
      this.displayState.set(key, target);
      return target;
    }

    const zoomFactor = clamp((zoom - SMOOTH_MIN_ZOOM) / 3.4, 0, 1);
    const timeConstant = 44 + zoomFactor * 42;
    let alpha = 1 - Math.exp(-dt / timeConstant);
    if (distance > 50) {
      alpha = Math.max(alpha, Math.min(0.9, distance / 120));
    }
    alpha = clamp(alpha, 0.18, 0.92);

    const next = {
      x: previous.x + (target.x - previous.x) * alpha,
      y: previous.y + (target.y - previous.y) * alpha,
      angle: lerpAngleDegrees(previous.angle, target.angle, Math.min(1, alpha * 1.25)),
      updatedAt: now,
    };
    this.displayState.set(key, next);
    return next;
  }

  pruneDisplayState() {
    if (!this.displayState.size) return;
    for (const key of this.displayState.keys()) {
      if (!this.displayStateSeen.has(key)) {
        this.displayState.delete(key);
      }
    }
    this.displayStateSeen.clear();
  }

  ensureMeshGroup(mode, count) {
    const current = this.meshGroups.get(mode);
    if (current && current.userData.capacity >= count) {
      if (!current.matrixBuffer || current.matrixBuffer.length < current.userData.capacity * 16) {
        current.matrixBuffer = new Float32Array(current.userData.capacity * 16);
      }
      return current;
    }
    if (current) {
      for (const mesh of current.meshes) {
        mesh.removeFromParent();
      }
    }

    const template = this.templates.get(mode);
    if (!template?.parts?.length) return null;

    const capacity = nextCapacity(count);
    const meshes = template.parts.map((part, index) => {
      const mesh = new THREE.InstancedMesh(part.geometry, part.material, capacity);
      mesh.name = `${this.id}-${mode}-${index}-${part.name}`;
      mesh.frustumCulled = false;
      mesh.matrixAutoUpdate = false;
      mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
      mesh.count = 0;
      mesh.visible = this.visible;
      this.scene.add(mesh);
      return mesh;
    });
    const group = {
      mode,
      meshes,
      matrixBuffer: new Float32Array(capacity * 16),
      userData: { capacity },
    };
    this.meshGroups.set(mode, group);
    return group;
  }

  updateInstances() {
    this.publishDebug();
    if (!this.ready) return;
    this.chooseOrigin();
    if (!this.originReady) return;

    const { buckets } = this.vehicleBuckets();
    const frame = this.vehicleFrame?.kind === "vehicle-frame" ? this.vehicleFrame : null;
    const now = typeof performance !== "undefined" ? performance.now() : Date.now();
    const dt = this.lastInstanceUpdateAt ? now - this.lastInstanceUpdateAt : 16;
    this.lastInstanceUpdateAt = now;
    this.displayStateSeen.clear();
    for (const mode of this.modes) {
      const vehicles = buckets.get(mode) || [];
      const group = this.ensureMeshGroup(mode, vehicles.length);
      if (!group) continue;
      const template = this.templates.get(mode);
      const yawOffset = template?.yawOffset || 0;

      for (const mesh of group.meshes) {
        mesh.count = vehicles.length;
      }

      for (let i = 0, count = vehicles.length; i < count; i++) {
        const vehicle = frame ? null : vehicles[i];
        const frameIndex = frame ? vehicles[i] : -1;
        const webX = frame ? frame.xs[frameIndex] : vehicle.webMercator[0];
        const webY = frame ? frame.ys[frameIndex] : vehicle.webMercator[1];
        const rawAngle = frame ? frame.angles?.[frameIndex] : vehicle.angle;
        const key = this.displayKeyForVehicle(mode, frame, frameIndex, vehicle);
        const display = this.smoothVehicleState(key, webX, webY, rawAngle, now, dt);
        const x = display.x - this.origin[0];
        const y = display.y - this.origin[1];
        const angle = THREE.MathUtils.degToRad(display.angle) + yawOffset;
        this.tmpPosition.set(x, y, 0.18);
        this.tmpQuaternion.setFromAxisAngle(this.zAxis, angle);
        this.tmpScale.setScalar(this.vehicleScale);
        this.tmpMatrix.compose(this.tmpPosition, this.tmpQuaternion, this.tmpScale);
        this.tmpMatrix.toArray(group.matrixBuffer, i * 16);
      }

      const matrixRange = group.matrixBuffer.subarray(0, vehicles.length * 16);
      for (const mesh of group.meshes) {
        if (matrixRange.length) {
          mesh.instanceMatrix.array.set(matrixRange);
        }
        if (mesh.instanceMatrix.updateRange) {
          mesh.instanceMatrix.updateRange.offset = 0;
          mesh.instanceMatrix.updateRange.count = matrixRange.length;
        }
        mesh.instanceMatrix.needsUpdate = true;
      }
    }

    this.pruneDisplayState();
    this.map?.triggerRepaint?.();
    this.publishDebug(true);
  }

  dispose() {
    for (const group of this.meshGroups.values()) {
      for (const mesh of group.meshes) {
        mesh.removeFromParent();
      }
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
