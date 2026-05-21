import * as THREE from "three";
import { Layer, MAP_EVENT } from "@/mymap/index.js";
import { LineSegmentPolygonGeometry, LinePolygonMaterial } from "@/mymap/geometry/LinePolygon.js";

export class HighlightSegmentLayer extends Layer {
  lineWidth = 32; // Slightly wider than standard segments to pop out
  center = [0, 0];

  constructor(opt = {}) {
    super({ zIndex: 1000, ...opt }); // Run on top of NetworkLayer (zIndex: 999)
    this.lineWidth = opt.lineWidth || this.lineWidth;

    this.geometry = new THREE.BufferGeometry();
    this.material = new LinePolygonMaterial({
      lineWidth: this.lineWidth,
      lineOffset: 0,
      useFlowControl: false,
      resolution: new THREE.Vector2(1, 1),
      color: 0x10b981, // Premium Emerald Green
      side: THREE.DoubleSide,
    });
    this.mesh = new THREE.Mesh(this.geometry, this.material);
    this.mesh.position.z = 0.5; // Fine-tune elevation to prevent z-fighting
    this.mesh.visible = false; // Initially invisible
    this.scene.add(this.mesh);
  }

  on(type, data) {
    if (type === MAP_EVENT.UPDATE_CENTER) {
      if (this.center && this.center[0] !== 0) {
        const [x, y] = this.map.WebMercatorToCanvasXY(this.center[0], this.center[1]);
        this.mesh.position.set(x, y, this.mesh.position.z);
      }
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

  setData(link) {
    if (!link) {
      this.center = [0, 0];
      this.mesh.visible = false;
      if (this.geometry) this.geometry.dispose();
      this.geometry = new THREE.BufferGeometry();
      this.mesh.geometry = this.geometry;
      return;
    }
    try {
      this.center = [link.from.x, link.from.y];
      if (this.geometry) this.geometry.dispose();

      this.geometry = new LineSegmentPolygonGeometry([
        {
          pickColor: new THREE.Color(1),
          links: [link],
          center: this.center,
        },
      ]);
      this.geometry.needsUpdate = true;
      this.mesh.geometry = this.geometry;
      this.mesh.visible = true;
      this.syncResolution();
      this.on(MAP_EVENT.UPDATE_CENTER, {});
    } catch (error) {
      console.error("Error setting data on HighlightSegmentLayer:", error);
    }
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

  dispose() {
    if (this.geometry) this.geometry.dispose();
    if (this.material) this.material.dispose();
    super.dispose();
  }
}
