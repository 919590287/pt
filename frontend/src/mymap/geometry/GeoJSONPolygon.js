import * as THREE from "three";

export class GeoJSONPolygonListGeometry extends THREE.BufferGeometry {
  constructor(polygonList = [], propertiesLabels = {}, valueKey = "") {
    super();
    this.type = "GeoJSONPolygonListGeometry";
    this.isGeoJSONPolygonListGeometry = true;

    this.valueMap = {};

    const propertiesKeyList = [];

    // buffers
    const indices = [];
    const vertices = [];
    const normals = [];
    const uvs = [];
    const values = [];
    for (let i = 0; i < polygonList.length; i++) {
      const { shape, value } = getShape(polygonList[i]);
      addShape(shape, value);
    }
    // build geometry
    this.setIndex(indices);
    this.setAttribute("position", new THREE.Float32BufferAttribute(vertices, 3));
    // this.setAttribute('normal', new THREE.Float32BufferAttribute(normals, 3));
    this.setAttribute("uv", new THREE.Float32BufferAttribute(uvs, 2));
    this.noValueAttribute = new THREE.Float32BufferAttribute(values, 1);
    this.setAttribute("value", this.noValueAttribute);
    this.computeVertexNormals();
    this.computeBoundingBox();
    // helper functions
    function addShape(shape, value) {
      const points = shape.extractPoints(12);

      let shapeVertices = points.shape;
      const shapeHoles = points.holes;

      // 把所有路径变成顺时针
      if (THREE.ShapeUtils.isClockWise(shapeVertices) === false) {
        shapeVertices = shapeVertices.reverse();
      }

      for (let i = 0, l = shapeHoles.length; i < l; i++) {
        const shapeHole = shapeHoles[i];
        if (THREE.ShapeUtils.isClockWise(shapeHole) === true) {
          shapeHoles[i] = shapeHole.reverse();
        }
      }
      // 根据路径和空洞生成面
      const faces = THREE.ShapeUtils.triangulateShape(shapeVertices, shapeHoles);
      for (let i = 0, l = shapeHoles.length; i < l; i++) {
        const shapeHole = shapeHoles[i];
        shapeVertices = shapeVertices.concat(shapeHole);
      }

      // 添加底面
      // {
      //   const indicesOffset = vertices.length / 3;
      //   // 添加点，法向量，uv，保存点对应的valueKey
      //   for (let i = 0, l = shapeVertices.length; i < l; i++) {
      //     const vertex = shapeVertices[i];
      //     vertices.push(vertex.x, vertex.y, 0);
      //     // normals.push(0, 0, 1);
      //     uvs.push(vertex.x, vertex.y); // world uvs

      //     propertiesKeyList.push(value);
      //     values.push(0);
      //   }
      //   // 添加面
      //   for (let i = 0, l = faces.length; i < l; i++) {
      //     const face = faces[i];
      //     const a = face[0] + indicesOffset;
      //     const b = face[1] + indicesOffset;
      //     const c = face[2] + indicesOffset;
      //     indices.push(a, b, c);
      //   }
      // }

      // 添加顶面
      {
        const indicesOffset = vertices.length / 3;
        // 添加点，法向量，uv，保存点对应的valueKey
        for (let i = 0, l = shapeVertices.length; i < l; i++) {
          const vertex = shapeVertices[i];
          vertices.push(vertex.x, vertex.y, 1);
          // normals.push(0, 0, 1);
          uvs.push(vertex.x, vertex.y); // world uvs

          propertiesKeyList.push(value);
          values.push(0);
        }
        // 添加面
        for (let i = 0, l = faces.length; i < l; i++) {
          const face = faces[i];
          const a = face[0] + indicesOffset;
          const b = face[1] + indicesOffset;
          const c = face[2] + indicesOffset;
          indices.push(a, b, c);
        }
      }

      // 添加外墙壁
      {
        const indicesOffset = vertices.length / 3;
        const shapeList = [...points.shape, points.shape[0]];
        for (let i = 0, l = shapeList.length - 1; i < l; i++) {
          const vertex1 = shapeList[i];
          const vertex2 = shapeList[i + 1];

          vertices.push(vertex1.x, vertex1.y, 0);
          vertices.push(vertex1.x, vertex1.y, 1);
          vertices.push(vertex2.x, vertex2.y, 0);
          vertices.push(vertex2.x, vertex2.y, 1);

          propertiesKeyList.push(value);
          propertiesKeyList.push(value);
          propertiesKeyList.push(value);
          propertiesKeyList.push(value);

          values.push(0);
          values.push(0);
          values.push(0);
          values.push(0);

          // normals.push(0, 0, -1);
          // normals.push(0, 0, -1);
          // normals.push(0, 0, -1);
          // normals.push(0, 0, -1);
          uvs.push(vertex1.x, vertex1.y); // world uvs
          uvs.push(vertex1.x, vertex1.y); // world uvs
          uvs.push(vertex2.x, vertex2.y); // world uvs
          uvs.push(vertex2.x, vertex2.y); // world uvs

          const a = i * 4 + indicesOffset;
          const b = i * 4 + 1 + indicesOffset;
          const c = i * 4 + 2 + indicesOffset;
          const d = i * 4 + 3 + indicesOffset;

          indices.push(a, b, c);
          indices.push(c, b, d);
        }
      }

      // 添加孔洞墙壁
      for (const holes of points.holes) {
        const indicesOffset = vertices.length / 3;
        const holeList = [...holes, holes[0]];
        for (let i = 0, l = holeList.length - 1; i < l; i++) {
          const vertex1 = holeList[i];
          const vertex2 = holeList[i + 1];

          vertices.push(vertex1.x, vertex1.y, 0);
          vertices.push(vertex1.x, vertex1.y, 1);
          vertices.push(vertex2.x, vertex2.y, 0);
          vertices.push(vertex2.x, vertex2.y, 1);

          propertiesKeyList.push(value);
          propertiesKeyList.push(value);
          propertiesKeyList.push(value);
          propertiesKeyList.push(value);

          values.push(0);
          values.push(0);
          values.push(0);
          values.push(0);

          // normals.push(0, 0, -1);
          // normals.push(0, 0, -1);
          // normals.push(0, 0, -1);
          // normals.push(0, 0, -1);
          uvs.push(vertex1.x, vertex1.y); // world uvs
          uvs.push(vertex1.x, vertex1.y); // world uvs
          uvs.push(vertex2.x, vertex2.y); // world uvs
          uvs.push(vertex2.x, vertex2.y); // world uvs

          const a = i * 4 + indicesOffset;
          const b = i * 4 + 1 + indicesOffset;
          const c = i * 4 + 2 + indicesOffset;
          const d = i * 4 + 3 + indicesOffset;

          indices.push(a, b, c);
          indices.push(c, b, d);
        }
      }
    }

    function getShape(array) {
      const value = array[0];
      const shapeArray = array.slice(1);
      let shape = null;
      for (let j = 0, l = shapeArray.length, size = shapeArray[0]; j < l; j += 1 + size, size = shapeArray[j]) {
        const v = shapeArray.slice(j + 1, j + 1 + size);
        const points = [];
        for (let k = 0, l3 = v.length / 2; k < l3; k++) {
          points[points.length] = new THREE.Vector2(v[k * 2 + 0], v[k * 2 + 1]);
        }
        if (!shape) {
          shape = new THREE.Shape(points);
        } else {
          const holePath = new THREE.Path(points);
          shape.holes.push(holePath);
        }
      }
      return { shape, value };
    }

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

export class GeoJSONPolygonMaterial extends THREE.Material {
  constructor(argu) {
    super();
    this.isGeoJSONLineMaterial = true;
    const { color = 0xff0000, opacity = 1, size = 50, colorBar = null, ...params } = argu || {};
    this.defines = {
      USE_COLOR_BAR: !!colorBar,
      USE_3D: false,
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
      colorBar: {
        value: !!colorBar ? textureLoader.load(colorBar.getImage()) : null,
      },
      minValue: {
        value: !!colorBar ? colorBar.min : 0,
      },
      maxValue: {
        value: !!colorBar ? colorBar.max : 1,
      },
      scale3D: {
        value: 1,
      },
    };
    this.vertexShader = `
      #include <common>
      #include <logdepthbuf_pars_vertex>


      attribute float value;
      
      varying vec3 vColor;
      varying vec2 vUv;
      varying float vValue;

      uniform float size;
      uniform mat3 uvTransform;
      uniform float maxValue;
      uniform float minValue;

      uniform float scale3D;

      void main() {
        vValue = value;

        vec3 transformed = position;
        
        #ifdef USE_3D
          transformed.z *= value * scale3D;
        #else
          transformed.z = 0.0;
        #endif

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
          diffuseColor.rgb = barDiffuseColor.rgb;
          diffuseColor.a *= barDiffuseColor.a;
        #endif

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

export class GeoJSONPolygonBorderListGeometry extends THREE.BufferGeometry {
  constructor(polygonList, propertiesLabels, valueKey) {
    super();
    this.type = "GeoJSONPolygonBorderListGeometry";
    this.isGeoJSONPolygonBorderListGeometry = true;

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

    for (let i = 0; i < polygonList.length; i++) {
      const { shape, value } = getShape(polygonList[i]);
      const points = shape.extractPoints(12);
      let shapeVertices = points.shape;
      const shapeHoles = points.holes;
      // 把所有路径变成顺时针
      if (THREE.ShapeUtils.isClockWise(shapeVertices) === false) {
        shapeVertices = shapeVertices.reverse();
      }
      addLine(shapeVertices, value);

      for (let i = 0, l = shapeHoles.length; i < l; i++) {
        let shapeHole = shapeHoles[i];
        if (THREE.ShapeUtils.isClockWise(shapeHole) === true) {
          shapeHole = shapeHole.reverse();
        }
        addLine(shapeHole, value);
      }
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

    function addLine(points, value) {
      let distance = 0;
      for (let i2 = 0, l2 = points.length; i2 < l2; i2++) {
        let prev = points[i2 - 1];
        let that = points[i2];
        let next = points[i2 + 1];
        if (i2 === 0) {
          prev = that.clone().multiplyScalar(2).sub(next);
        }
        if (i2 >= l2 - 1) {
          next = that.clone().multiplyScalar(2).sub(prev);
        }

        attrPosition[attrPosition.length] = that.x;
        attrPosition[attrPosition.length] = that.y;
        attrPosition[attrPosition.length] = 1;
        attrPosition[attrPosition.length] = that.x;
        attrPosition[attrPosition.length] = that.y;
        attrPosition[attrPosition.length] = 1;

        attrStartPosition[attrStartPosition.length] = prev.x;
        attrStartPosition[attrStartPosition.length] = prev.y;
        attrStartPosition[attrStartPosition.length] = prev.x;
        attrStartPosition[attrStartPosition.length] = prev.y;

        attrEndPosition[attrEndPosition.length] = next.x;
        attrEndPosition[attrEndPosition.length] = next.y;
        attrEndPosition[attrEndPosition.length] = next.x;
        attrEndPosition[attrEndPosition.length] = next.y;

        propertiesKeyList[propertiesKeyList.length] = value;
        propertiesKeyList[propertiesKeyList.length] = value;

        attrValue[attrValue.length] = 0;
        attrValue[attrValue.length] = 0;

        attrDistance[attrDistance.length] = distance;
        attrDistance[attrDistance.length] = distance;

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

        distance += that.distanceTo(next);
      }
    }

    function getShape(array) {
      const value = array[0];
      const shapeArray = array.slice(1);
      let shape = null;
      for (let j = 0, l = shapeArray.length, size = shapeArray[0]; j < l; j += 1 + size, size = shapeArray[j]) {
        const v = shapeArray.slice(j + 1, j + 1 + size);
        const points = [];
        for (let k = 0, l3 = v.length / 2; k < l3; k++) {
          points[points.length] = new THREE.Vector2(v[k * 2 + 0], v[k * 2 + 1]);
        }
        if (!shape) {
          shape = new THREE.Shape(points);
        } else {
          const holePath = new THREE.Path(points);
          shape.holes.push(holePath);
        }
      }
      return { shape, value };
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

export class GeoJSONPolygonBorderMaterial extends THREE.Material {
  constructor(argu) {
    super();
    this.isGeoJSONPolygonBorderMaterial = true;
    const { color = 0xff0000, opacity = 1, lineStyle = LINE_STYLE.SOLID, lineWidth = 50, lineOffset = 0, colorBar = null, ...params } = argu || {};
    this.defines = {
      USE_COLOR_BAR: !!colorBar,
      USE_3D: false,
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
      min3DValue: {
        value: 0,
      },
      max3DValue: {
        value: 1,
      },
      scale3D: {
        value: 100,
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
      uniform float maxValue;
      uniform float minValue;

      uniform float scale3D;

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
          
        #ifdef USE_3D
          transformed.z *= value * scale3D;
        #else
          transformed.z = 0.0;
        #endif

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
      uniform float maxValue;
      uniform float minValue;
      
      varying vec3 vColor;
      varying vec2 vUv;
      varying float vValue;
      varying float vDistance;

      void main() {
        vec4 diffuseColor = vec4( diffuse, opacity );
        
        #include <logdepthbuf_fragment>

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

export class GeoJSONPolygon {}
