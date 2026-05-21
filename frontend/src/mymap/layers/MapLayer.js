import { MAP_EVENT, Layer } from "../index";
import * as THREE from "three";

const EARTH_RADIUS = 20037508.3427892;
// 去掉404报错提示 -404 -Access-Control-Allow-Origin
const Loader = new THREE.TextureLoader();

// 地图图层类
export class MapLayer extends Layer {
  name = "MapLayer";

  /**
   * 地图图层使用的瓦片类
   * @type {Function}
   */
  tileClass = MapTile;

  opacity = 1;

  /**
   * 获取当前缩放级别
   * 根据瓦片类的min_zoom和max_zoom来现在最大和最小的缩放级别
   * @returns {number}
   */
  get zoom() {
    let zoom = Math.floor(this.map.zoom);
    if (zoom > this.tileClass.max_zoom) zoom = this.tileClass.max_zoom;
    else if (zoom < this.tileClass.min_zoom) zoom = this.tileClass.min_zoom;
    return zoom;
  }

  // 地图瓦片分组Map
  get zoomMap() {
    if (!this._zoomMap) {
      this._zoomMap = {};
      for (let i = this.tileClass.min_zoom; i <= this.tileClass.max_zoom; i++) {
        this._zoomMap[i] = {
          scene: new THREE.Group(),
          tileMap: {},
          show: false,
        };
      }
    }
    return this._zoomMap;
  }

  constructor(opt) {
    super(opt);
    this.setTileClass(opt.tileClass);
    this.setOpacity(opt.opacity);
  }

  setOpacity(opacity) {
    if (opacity === undefined) {
      this.opacity = 1;
    } else {
      this.opacity = opacity;
    }
    for (const zoom of Object.values(this.zoomMap)) {
      for (const tile of Object.values(zoom.tileMap)) {
        tile.opacity = this.opacity;
      }
    }
  }

  // 设置瓦片类
  setTileClass(tileClass) {
    if (new tileClass() instanceof MapTile) {
      this.tileClass = tileClass;
      this._zoomMap = null;
      this.clearScene();
      if (this.map) {
        this.map.setBackground(new THREE.Color(this.tileClass.background));
        this.loadMesh();
        this.setShowZoom(this.zoom);
        this.setMeshPosition(this.map.center);
      }
    } else {
      console.error("tileClass must be a subclass of MapTile");
    }
  }

  // 地图加载完成回调
  async onAdd(map) {
    super.onAdd(map);
    if (this.map) {
      this.map.setBackground(new THREE.Color(this.tileClass.background));
      this.loadMesh();
      this.setShowZoom(this.zoom);
      this.setMeshPosition(this.map.center);
    }
  }

  // 地图事件回调
  on(type, data) {
    if (type == MAP_EVENT.UPDATE_ZOOM) {
      if (this.map) {
        this.loadMesh();
        this.setShowZoom(this.zoom);
        this.setMeshPosition(this.map.center);
      }
    }
    if (type == MAP_EVENT.UPDATE_CENTER) {
      if (this.map) {
        this.loadMesh();
        this.setShowZoom(this.zoom);
        this.setMeshPosition(this.map.center);
      }
    }
  }

  // 显示指定缩放级别的瓦片
  setShowZoom(zoom) {
    for (const z of Object.keys(this.zoomMap)) {
      const map = this.zoomMap[z];
      if (z == zoom) {
        map.show = true;
        map.scene.position.setZ(0);
        this.scene.add(map.scene);
        // } else if (z == zoom + 1) {
        //   map.show = true;
        //   map.scene.position.setZ(-0.1);
        //   this.scene.add(map.scene);
      } else if (map.show) {
        map.show = false;
        this.scene.remove(map.scene);
      }
    }
  }

  // 设置地图瓦片位置
  setMeshPosition([cx, cy]) {
    for (const z of Object.keys(this.zoomMap)) {
      const map = this.zoomMap[z];
      for (const tk of Object.keys(map.tileMap)) {
        const tile = map.tileMap[tk];
        const [x, y] = this.map.WebMercatorToCanvasXY(tile.x, tile.y, cx, cy);
        tile.mesh.position.set(x, y, tile.z);
      }
    }
  }

  // 加载瓦片
  loadMesh() {
    const zoom = this.zoom;
    const { row, col, size } = this.map.getTileRangeByZoom(zoom);
    const crow = (row[0] + row[1]) / 2;
    const ccol = (col[0] + col[1]) / 2;
    let urlList = [];
    for (let i = row[0]; i <= row[1]; i++) {
      for (let j = col[0]; j <= col[1]; j++) {
        const key = `${i}_${j}`;
        let tile = this.zoomMap[zoom].tileMap[key];
        if (!this.zoomMap[zoom].tileMap[key]) {
          tile = new this.tileClass(zoom, i, j, size);
          tile.opacity = this.opacity;
          this.zoomMap[zoom].tileMap[key] = tile;
          this.zoomMap[zoom].scene.add(tile.mesh);
        }
        urlList.push({ level: Math.abs(crow - i) + Math.abs(ccol - j), tile: tile });
      }
    }
    urlList
      .sort((a, b) => a.level - b.level)
      .forEach((v) => {
        if (v.tile.loadStatus == 1) {
          v.tile.loadMap();
        }
      });
  }
}

// 瓦片类
export class MapTile {
  // 瓦片名称
  static name = "MapTile";
  // 地图背景色
  static background = 0xd9ecff;
  // 瓦片的最大缩放级别
  static max_zoom = 18;
  // 瓦片的最小缩放级别
  static min_zoom = 5;
  // x偏移量
  static x_offset = 0;
  // y偏移量
  static y_offset = 0;

  _loadNum = 0;

  // 加载状态 1未加载 2加载成功 3加载失败
  _loadStatus = 1;

  get loadStatus() {
    return this._loadStatus;
  }

  // 瓦片的行号
  get row() {
    return this._row;
  }
  set row(v) {
    this._row = v;
  }

  // 瓦片的列号
  get col() {
    return this._col;
  }
  set col(v) {
    this._col = v;
  }

  // 瓦片的缩放级别
  get zoom() {
    return this._zoom;
  }
  set zoom(v) {
    this._zoom = v;
  }

  // 瓦片的大小
  get size() {
    return this._size;
  }
  set size(v) {
    this._size = v;
  }

  // 瓦片的x坐标
  get x() {
    return this._x + this.constructor.x_offset;
  }

  // 瓦片的y坐标
  get y() {
    return this._y + this.constructor.y_offset;
  }

  // 瓦片的z坐标
  get z() {
    return this._z;
  }

  // 瓦片的mesh
  get mesh() {
    if (!this._mesh) {
      let material = new THREE.MeshBasicMaterial({
        transparent: true,
        // side: THREE.DoubleSide, //双面显示
        color: 0xffffff,
        opacity: 0,
        // color: 0x000000,
      });
      let geometry = new THREE.PlaneGeometry(this._size, this._size);
      const m4 = new THREE.Matrix4().makeTranslation(this._size / 2, -this._size / 2, 0);
      geometry.applyMatrix4(m4);
      this._mesh = new THREE.Mesh(geometry, material);
      // this._mesh.add(new THREE.AxesHelper(1000));
      return this._mesh;
    } else {
      return this._mesh;
    }
  }

  _opacity = 1;

  get opacity() {
    return this._opacity;
  }

  set opacity(v) {
    this._opacity = v;
    this.mesh.material.setValues({ opacity: v, transparent: v !== 1 });
  }

  // 加载瓦片贴图
  async loadMap() {
    try {
      this._loadNum++;
      const texture = await Loader.loadAsync(this.getUrl());
      this.mesh.material.setValues({ map: texture, opacity: this._opacity, transparent: this._opacity !== 1 });
      this.mesh.material.needsUpdate = true;
      this._loadStatus = 2;
    } catch (error) {
      if (this._loadNum <= 1) {
        await new Promise((resolve) => setTimeout(resolve, 2000 * Math.random()));
        this.loadMap();
      } else {
        this._loadStatus = 3;
      }
    }
  }

  getUrl() {
    return "";
  }

  constructor(zoom, row, col, size) {
    this.zoom = zoom;
    this.row = row;
    this.col = col;
    this.size = size;

    this._x = (row * (EARTH_RADIUS * 2)) / Math.pow(2, zoom) - EARTH_RADIUS;
    this._y = EARTH_RADIUS - (col * (EARTH_RADIUS * 2)) / Math.pow(2, zoom);
    // let zd = this.constructor.max_zoom - this.constructor.min_zoom;
    // this._z = (zd - zoom) / zd / 100;
    this._z = 0;
  }
}

export function MapStyleFactory(params = {}) {
  const defaultParams = {
    style_name: "",
    background: 0xf5f5f5,
    max_zoom: 18,
    min_zoom: 0,
    x_offset: 0,
    y_offset: 0,
    getUrl: function () {
      return `https://basemaps.cartocdn.com/light_all/${this.zoom}/${this.row}/${this.col}.png`;
    },
  };
  const { style_name, background, max_zoom, min_zoom, x_offset, y_offset, ...methods } = Object.assign({}, defaultParams, params);
  const Tile = class extends MapTile {
    static style_name = style_name;
    static background = background;
    static max_zoom = max_zoom;
    static min_zoom = min_zoom;
    static x_offset = x_offset;
    static y_offset = y_offset;
  };
  for (const key in methods) {
    Tile.prototype[key] = methods[key];
  }
  return Tile;
}

export const MAP_LAYER_STYLE = (window.MAP_LAYER_STYLE || [{}]).map(MapStyleFactory);

export const DEFAULT_MAP_LAYER_STYLE = MAP_LAYER_STYLE[window.DEFAULT_MAP_LAYER_STYLE_INDEX] || MAP_LAYER_STYLE[0];
