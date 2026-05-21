
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";

// 模型池
export class ModelPool {
  loader = false;

  modelPool = null;

  modelObj = null;

  constructor(modelsUrl) {
    const loader = new GLTFLoader();
    const pList = [];
    for (const [name, url] of Object.entries(modelsUrl)) {
      pList.push(
        new Promise((resolve, reject) => {
          loader.load(
            url,
            (gltf) => {
              resolve([name, gltf.scene]);
            },
            null,
            () => {
              resolve([name, null]);
            }
          );
        })
      );
    }
    Promise.all(pList).then((res) => {
      this.modelPool = new Map(res.map((v) => [v[0], new Array()]));
      this.modelObj = new Map(res);
      this.loader = true;
    });
  }

  // 获取模型
  take(name) {
    try {
      return this.modelPool.get(name).shift() || this.modelObj.get(name).clone();
    } catch (error) {
      return null;
    }
  }

  // 回收模型
  still(name, model) {
    try {
      const list = this.modelPool.get(name);
      list[list.length] = model;
      return 1;
    } catch (error) {
      return -1;
    }
  }

  dispose() {
    for (const name of this.modelObj.keys()) {
      this.modelObj.get(name).traverse((obj) => obj.dispose && obj.dispose());
      if (this.modelPool.has(name)) {
        this.modelPool.get(name).forEach(v2 => v2.traverse && v2.traverse((obj) => obj.dispose && obj.dispose()))
        this.modelPool.get(name).length = 0
      }
    }
    this.modelObj.clear();
    this.modelPool.clear();
  }
}