import * as THREE from "three";

export class GeoJSONPointListGeometry extends THREE.BufferGeometry {
  constructor(pointArray = [], propertiesLabels = {}, valueKey = "") {
    super();
    this.type = "GeoJSONPointListGeometry";
    this.isGeoJSONPointListGeometry = true;

    this.valueMap = {};

    const propertiesKeyList = [];

    const attrPosition = new Array();
    const attrNormal = new Array();
    const attrSide = new Array();
    const attrValue = new Array();
    const attrIndex = new Array();
    for (let i1 = 0, l1 = Math.floor(pointArray.length / 3); i1 < l1; i1++) {
      const x = pointArray[i1 * 3];
      const y = pointArray[i1 * 3 + 1];
      const value = pointArray[i1 * 3 + 2];
      for (let i2 = 0; i2 < 4; i2++) {
        attrPosition[attrPosition.length] = x;
        attrPosition[attrPosition.length] = y;
        attrPosition[attrPosition.length] = 0;
        attrNormal[attrNormal.length] = 0;
        attrNormal[attrNormal.length] = 0;
        attrNormal[attrNormal.length] = 1;
        attrSide[attrSide.length] = i2;
        attrValue[attrValue.length] = 0;
        propertiesKeyList[propertiesKeyList.length] = value;
      }

      attrIndex[attrIndex.length] = i1 * 4 + 0;
      attrIndex[attrIndex.length] = i1 * 4 + 1;
      attrIndex[attrIndex.length] = i1 * 4 + 3;
      attrIndex[attrIndex.length] = i1 * 4 + 0;
      attrIndex[attrIndex.length] = i1 * 4 + 3;
      attrIndex[attrIndex.length] = i1 * 4 + 2;
    }
    this.setAttribute("position", new THREE.Float32BufferAttribute(attrPosition, 3));
    this.setAttribute("normal", new THREE.Float32BufferAttribute(attrNormal, 3));
    this.setAttribute("side", new THREE.Float32BufferAttribute(attrSide, 1));
    this.noValueAttribute = new THREE.Float32BufferAttribute(attrValue, 1);
    this.setAttribute("value", this.noValueAttribute);
    this.setIndex(attrIndex);
    // this.computeVertexNormals();
    this.computeBoundingBox();

    this.propertiesKeyList = propertiesKeyList;
    this.setPropertiesLabels(propertiesLabels);
    this.setValueKey(valueKey);
  }

  setPropertiesLabels(propertiesLabels) {
    try {
      const map = {};
      for (const [label, item] of Object.entries(propertiesLabels)) {
        const l = this.propertiesKeyList.map((v) => Number(item.values[v]));
        map[label] = new THREE.Float32BufferAttribute(l, 1);
      }
      this.valueMap = map;
      this.setValueKey(this._valuekey);
    } catch (error) {
      console.log(error);
      this.valueMap = {};
      this.setValueKey(this._valuekey);
    }
  }

  setValueKey(valueKey) {
    try {
      this._valuekey = valueKey;
      const attrValue = this.valueMap[valueKey];
      if (attrValue) {
        this.setAttribute("value", attrValue);
      } else {
        // mac系统中不能设置空数组，不然图像不显示
        // this.setAttribute("value", new THREE.Float32BufferAttribute([], 1));
        this.setAttribute("value", this.noValueAttribute);
      }
    } catch (error) {
      console.log(error);
    }
  }
}

export class GeoJSONPointMaterial extends THREE.Material {
  constructor(argu) {
    super();
    this.isGeoJSONLineMaterial = true;
    const { color = 0xff0000, opacity = 1, size = 50, map = null, colorBar = null, ...params } = argu || {};
    // this.alphaTest = 0.1;
    // this.transparent = true;
    // this.depthWrite = false;
    this.defines = {
      USE_COLOR_BAR: !!colorBar,
      USE_MAP: !!map,
    };
    this.uniforms = {
      diffuse: {
        value: new THREE.Color(color),
      },
      opacity: {
        value: opacity,
      },
      size: {
        value: size,
      },
      map: {
        value: map,
      },
      uvTransform: {
        value: new THREE.Matrix3(),
      },
      colorBar: {
        value: !!colorBar ? textureLoader.load(colorBar.getImage()) : null,
      },
      minValue: {
        value: !!colorBar ? colorBar.min : 0,
      },
      maxValue: {
        value: !!colorBar ? colorBar.max : 1,
      },
    };
    this.vertexShader = `
      #include <common>
      #include <logdepthbuf_pars_vertex>

      attribute float side;
      attribute float value;
      
      varying vec3 vColor;
      varying vec2 vUv;
      varying float vValue;

      uniform float size;
      uniform mat3 uvTransform;

      void main() {
        vValue = value;

        vec3 transformed = vec3(1.0);

        // 0 2
        // 1 3 

        if(side == 0.0) {
          transformed.x = position.x - size / 2.0;
          transformed.y = position.y + size / 2.0;
          transformed.z = position.z;
          vUv = ( uvTransform * vec3( 0.0, 1.0, 1.0 ) ).xy;
        } else if(side == 1.0) {
          transformed.x = position.x - size / 2.0;
          transformed.y = position.y - size / 2.0;
          transformed.z = position.z;
          vUv = ( uvTransform * vec3( 0.0, 0.0, 1.0 ) ).xy;
        } else if(side == 2.0) {
          transformed.x = position.x + size / 2.0;
          transformed.y = position.y + size / 2.0;
          transformed.z = position.z;
          vUv = ( uvTransform * vec3( 1.0, 1.0, 1.0 ) ).xy;
        } else if(side == 3.0) {
          transformed.x = position.x + size / 2.0;
          transformed.y = position.y - size / 2.0;
          transformed.z = position.z;
          vUv = ( uvTransform * vec3( 1.0, 0.0, 1.0 ) ).xy;
        }

        gl_Position = projectionMatrix * modelViewMatrix * vec4( transformed, 1.0 );

        #include <logdepthbuf_vertex>

      }
    `;
    this.fragmentShader = `
      #include <common>
      #include <logdepthbuf_pars_fragment>


      uniform vec3 diffuse;
      uniform float opacity;
      uniform sampler2D map;
      uniform sampler2D colorBar;
      uniform float maxValue;
      uniform float minValue;
      
      varying vec3 vColor;
      varying vec2 vUv;
      varying float vValue;

      void main() {
        vec4 diffuseColor = vec4( diffuse, opacity );
        
        #include <logdepthbuf_fragment>
        
        #ifdef USE_COLOR_BAR
          float p = 0.0;
          if(maxValue != minValue) {
            p = (vValue - minValue) / (maxValue - minValue);
          }
          if(p> 1.0) p = 1.0;
          if(p< 0.0) p = 0.0;
          vec4 barDiffuseColor = texture2D(colorBar, vec2(p , 0.5));
          diffuseColor = barDiffuseColor;
          diffuseColor.a *= opacity;
        #endif

        #ifdef USE_MAP
          vec4 sampledDiffuseColor = texture2D(map, vUv);
          diffuseColor *= sampledDiffuseColor;
        #endif

        gl_FragColor = diffuseColor;
      }
    `;
    this.setValues(params);
  }

  setColorBar(colorBar) {
    if (colorBar) {
      this.defines.USE_COLOR_BAR = true;
      this.uniforms.colorBar.value = textureLoader.load(colorBar.getImage());
      this.uniforms.minValue.value = colorBar.min;
      this.uniforms.maxValue.value = colorBar.max;
    } else {
      this.defines.USE_COLOR_BAR = false;
      this.uniforms.colorBar.value = null;
      this.uniforms.minValue.value = 0;
      this.uniforms.maxValue.value = 1;
    }
    this.needsUpdate = true;
  }
}

export class GeoJSONPoint {}
