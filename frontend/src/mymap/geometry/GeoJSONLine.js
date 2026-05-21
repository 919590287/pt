import * as THREE from "three";

export class GeoJSONLineListGeometry extends THREE.BufferGeometry {
  constructor(lineList = [], propertiesLabels = {}, valueKey = "") {
    super();
    this.type = "GeoJSONLineListGeometry";
    this.isGeoJSONLineListGeometry = true;

    this.valueMap = {};

    const propertiesKeyList = [];

    const attrPosition = new Array();
    const attrStartPosition = new Array();
    const attrEndPosition = new Array();
    const attrSide = new Array();
    const attrDistance = new Array();
    const attrValue = new Array();
    const attrIndex = new Array();
    let indexOffset = 0;

    for (let i1 = 0, l1 = lineList.length; i1 < l1; i1++) {
      const value = lineList[i1][0];
      const array = lineList[i1].slice(1);
      addLine(array, value);
    }
    this.setAttribute("position", new THREE.Float32BufferAttribute(attrPosition, 3));
    this.setAttribute("startPosition", new THREE.Float32BufferAttribute(attrStartPosition, 2));
    this.setAttribute("endPosition", new THREE.Float32BufferAttribute(attrEndPosition, 2));
    this.setAttribute("side", new THREE.Int8BufferAttribute(attrSide, 1));
    this.noValueAttribute = new THREE.Float32BufferAttribute(attrValue, 1);
    this.setAttribute("value", this.noValueAttribute);
    this.setAttribute("distance", new THREE.Float32BufferAttribute(attrDistance, 1));
    this.setIndex(attrIndex);
    this.computeVertexNormals();
    this.computeBoundingBox();

    this.propertiesKeyList = propertiesKeyList;
    this.setPropertiesLabels(propertiesLabels);
    this.setValueKey(valueKey);

    function addLine(array, value) {
      for (let i2 = 0, l2 = array.length / 3; i2 < l2; i2++) {
        let prevX = array[i2 * 3 - 3];
        let prevY = array[i2 * 3 - 2];
        let prevL = array[i2 * 3 - 1];
        let thatX = array[i2 * 3 + 0];
        let thatY = array[i2 * 3 + 1];
        let thatL = array[i2 * 3 + 2];
        let nextX = array[i2 * 3 + 3];
        let nextY = array[i2 * 3 + 4];
        let nextL = array[i2 * 3 + 5];
        if (i2 === 0) {
          prevX = thatX * 2 - nextX;
          prevY = thatY * 2 - nextY;
        }
        if (i2 >= l2 - 1) {
          nextX = thatX * 2 - prevX;
          nextY = thatY * 2 - prevY;
        }

        attrPosition[attrPosition.length] = thatX;
        attrPosition[attrPosition.length] = thatY;
        attrPosition[attrPosition.length] = 0;
        attrPosition[attrPosition.length] = thatX;
        attrPosition[attrPosition.length] = thatY;
        attrPosition[attrPosition.length] = 0;

        attrStartPosition[attrStartPosition.length] = prevX;
        attrStartPosition[attrStartPosition.length] = prevY;
        attrStartPosition[attrStartPosition.length] = prevX;
        attrStartPosition[attrStartPosition.length] = prevY;

        attrEndPosition[attrEndPosition.length] = nextX;
        attrEndPosition[attrEndPosition.length] = nextY;
        attrEndPosition[attrEndPosition.length] = nextX;
        attrEndPosition[attrEndPosition.length] = nextY;

        propertiesKeyList[propertiesKeyList.length] = value;
        propertiesKeyList[propertiesKeyList.length] = value;

        attrValue[attrValue.length] = 0;
        attrValue[attrValue.length] = 0;

        attrDistance[attrDistance.length] = thatL;
        attrDistance[attrDistance.length] = thatL;

        attrSide[attrSide.length] = -1;
        attrSide[attrSide.length] = 1;

        if (i2 < l2 - 1) {
          attrIndex[attrIndex.length] = indexOffset + 0;
          attrIndex[attrIndex.length] = indexOffset + 1;
          attrIndex[attrIndex.length] = indexOffset + 3;
          attrIndex[attrIndex.length] = indexOffset + 0;
          attrIndex[attrIndex.length] = indexOffset + 3;
          attrIndex[attrIndex.length] = indexOffset + 2;
        }
        indexOffset += 2;
      }
    }
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

export class GeoJSONLineMaterial extends THREE.Material {
  constructor(argu) {
    super();
    this.isGeoJSONLineMaterial = true;
    const { color = 0xff0000, opacity = 1, lineStyle = LINE_STYLE.SOLID, lineWidth = 50, lineOffset = 0, colorBar = null, ...params } = argu || {};
    // this.alphaTest = 0.1;
    // this.transparent = true;
    // this.depthWrite = false;
    this.defines = {
      USE_COLOR_BAR: !!colorBar,
    };
    this.uniforms = {
      diffuse: {
        value: new THREE.Color(color),
      },
      opacity: {
        value: opacity,
      },
      lineStyle: {
        value: lineStyle,
      },
      lineWidth: {
        value: lineWidth,
      },
      lineOffset: {
        value: lineOffset,
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
      attribute float distance;
      attribute vec2 startPosition;
      attribute vec2 endPosition;
      
      varying vec3 vColor;
      varying vec2 vUv;
      varying float vValue;
      varying float vDistance;

      uniform float lineWidth;
      uniform float lineOffset;
      uniform mat3 uvTransform;

      void main() {
        vValue = value;
        vDistance = distance;

        #ifdef USE_MAP
          vUv = ( uvTransform * vec3( uv, 1 ) ).xy;
        #endif
        
        vec3 transformed = vec3(1.0);

        float offset = lineWidth / 2.0 * side + lineOffset;

        float lenA = length(position.xy - startPosition);
        float lenB = length(position.xy - endPosition);

        if(lenA == 0. && lenB == 0.) {
          transformed = position;
        } else {
          vec2 dirA = normalize(position.xy - startPosition);
          vec2 dirB = normalize(position.xy - endPosition);

          if(lenA == 0.) {
            float angle = PI / 2.0;
            vec2 normal = vec2(-dirB.y, dirB.x);
            transformed = vec3(position.xy + normal * offset / sin(angle), position.z);
          } else if(lenB == 0.) {
            float angle = PI / 2.0;
            vec2 normal = vec2(dirA.y, -dirA.x);
            transformed = vec3(position.xy + normal * offset / sin(angle), position.z);
          } else {
            vec2 dir = normalize(dirB - dirA);
            vec2 normal = vec2(-dir.y, dir.x);
            float angle = mod(acos(dot(dirB, normal)), 3.14);
            if(angle < 0.2) angle = 0.2;
            if(angle > 2.94) angle = 2.94;
            transformed = vec3(position.xy + normal * offset / sin(angle), position.z);
          }
        }

        gl_Position = projectionMatrix * modelViewMatrix * vec4( transformed, 1.0 );

        #include <logdepthbuf_vertex>

      }
    `;
    this.fragmentShader = `
      #include <common>
      #include <logdepthbuf_pars_fragment>


      uniform float lineWidth;
      uniform float lineStyle;

      uniform vec3 diffuse;
      uniform float opacity;
      uniform sampler2D map;
      uniform sampler2D colorBar;
      uniform float maxValue;
      uniform float minValue;
      
      varying vec3 vColor;
      varying vec2 vUv;
      varying float vValue;
      varying float vDistance;

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

        if(lineStyle == ${Number(LINE_STYLE.DASHED).toFixed(1)}){
          float dl = mod(vDistance / (lineWidth * 3.0), 1.0);
          if(0.50 < dl && dl <= 1.0){
            diffuseColor.a = 0.0;
          }
        } else if(lineStyle == ${Number(LINE_STYLE.NONE).toFixed(1)}){
          diffuseColor.a = 0.0;
        }

        gl_FragColor = diffuseColor;

      }
    `;
    this.setValues(params);
  }

  setColorBar(colorBar) {
    if (!!colorBar) {
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

export class GeoJSONLine {}
