import * as THREE from "three";
import { Layer, MAP_EVENT } from "@/mymap/index.js";

import { LineSegmentPolygonGeometry, LinePolygonMaterial } from "@/mymap/geometry/LinePolygon.js";

export class NetworkLayer extends Layer {
  lineWidth = 20;
  lineOffset = 0;
  flowControl = false;
  flowMinWidth = 1;
  flowMaxWidth = 40;
  flowWidthStep = 20;
  center = [0, 0];

  constructor(opt) {
    super(opt);
    this.datasource = opt.datasource || "";
    this.lineWidth = opt.lineWidth || this.lineWidth;
    this.lineOffset = opt.lineOffset || this.lineOffset;
    this.flowControl = opt.flowControl ?? this.flowControl;
    this.flowWidthStep = opt.flowWidthStep || this.flowWidthStep;

    this.geometry = new LineSegmentPolygonGeometry();
    this.material = new LinePolygonMaterial({
      lineWidth: this.lineWidth,
      lineOffset: this.lineOffset,
      useFlowControl: this.flowControl,
      flowMinWidth: this.flowMinWidth,
      flowMaxWidth: this.flowMaxWidth,
      flowWidthStep: this.flowWidthStep,
      resolution: new THREE.Vector2(1, 1),
      color: 0x1f78b4,
      side: THREE.DoubleSide,
    });
    this.mesh = new THREE.Mesh(this.geometry, this.material);

    this.scene.add(this.mesh);
  }

  on(type, data) {
    if (type === MAP_EVENT.UPDATE_CENTER) {
      const [x, y] = this.map.WebMercatorToCanvasXY(this.center[0], this.center[1]);
      this.mesh.position.set(x, y, this.mesh.position.z);
    }
    if (type === MAP_EVENT.UPDATE_RENDERER_SIZE) {
      this.syncResolution(data);
    }
  }

  onAdd(map) {
    super.onAdd(map);
    this.syncResolution();
    this.on(MAP_EVENT.UPDATE_CENTER, {});
  }

  setData(data) {
    this.data = data;
    this.update();
  }

  setLineWidth(lineWidth) {
    const nextLineWidth = Number(lineWidth);
    if (!Number.isFinite(nextLineWidth)) return;

    this.lineWidth = nextLineWidth;
    if (this.material?.userData) {
      this.material.userData.lineWidth = this.lineWidth;
    }
    const uniform = this.material?.userData?.shader?.uniforms?.lineWidth;
    if (uniform) {
      uniform.value = this.lineWidth;
    }
  }

  setFlowControl(flowControl) {
    this.flowControl = !!flowControl;
    this.setMaterialValue("useFlowControl", this.flowControl);
    this.syncFlowRange();
    this.syncResolution();
  }

  setFlowWidthStep(flowWidthStep) {
    const nextFlowWidthStep = Number(flowWidthStep);
    if (!Number.isFinite(nextFlowWidthStep)) return;

    this.flowWidthStep = nextFlowWidthStep;
    this.setMaterialValue("flowWidthStep", this.flowWidthStep);
  }

  syncResolution(size) {
    const width = size?.width || this.map?.rootDoc?.clientWidth || window.innerWidth || 1;
    const height = size?.height || this.map?.rootDoc?.clientHeight || window.innerHeight || 1;
    let resolution = this.material?.userData?.resolution;
    if (!resolution?.isVector2) {
      resolution = new THREE.Vector2(width, height);
      if (this.material?.userData) {
        this.material.userData.resolution = resolution;
      }
    } else {
      resolution.set(width, height);
    }
    const uniform = this.material?.userData?.shader?.uniforms?.resolution;
    if (uniform) {
      if (uniform.value?.isVector2) {
        uniform.value.set(width, height);
      } else {
        uniform.value = resolution;
      }
    }
  }

  setMaterialValue(key, value) {
    if (this.material?.userData) {
      this.material.userData[key] = value;
    }
    const uniform = this.material?.userData?.shader?.uniforms?.[key];
    if (uniform) {
      uniform.value = value;
    }
  }

  syncFlowRange() {
    this.setMaterialValue("hasFlowData", !!this.geometry?.hasFlowData);
    this.setMaterialValue("flowMin", this.geometry?.flowMin ?? 0);
    this.setMaterialValue("flowMax", this.geometry?.flowMax ?? 0);
    this.setMaterialValue("flowMinWidth", this.flowMinWidth);
    this.setMaterialValue("flowMaxWidth", this.flowMaxWidth);
    this.setMaterialValue("flowWidthStep", this.flowWidthStep);
  }

  update() {
    try {
      const data = this.data;
      this.center = [data[0].from.x, data[0].from.y];
      if (this.geometry) this.geometry.dispose();

      this.geometry = new LineSegmentPolygonGeometry([
        {
          pickColor: new THREE.Color(1),
          links: data,
          center: this.center,
        },
      ]);
      this.geometry.needsUpdate = true;
      this.mesh.geometry = this.geometry;
      this.setLineWidth(this.lineWidth);
      this.syncFlowRange();
      this.setFlowControl(this.flowControl);
      this.syncResolution();

      this.on(MAP_EVENT.UPDATE_CENTER, {});
    } catch (error) {
      console.log(error);

      this.center = [0, 0];
      if (this.geometry) this.geometry.dispose();
      this.geometry = new THREE.BufferGeometry();
      this.geometry.needsUpdate = true;
      this.mesh.geometry = this.geometry;
    }
    this.on(MAP_EVENT.UPDATE_CENTER, {});
  }

  dispose() {
    if (this.geometry) this.geometry.dispose();
    if (this.material) this.material.dispose();
    super.dispose();
  }
}
